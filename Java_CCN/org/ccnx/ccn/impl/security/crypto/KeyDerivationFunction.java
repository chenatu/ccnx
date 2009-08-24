package org.ccnx.ccn.impl.security.crypto;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This class takes a master symmetric key, and derives from it a key
 * and initialization vector to be used to encrypt a specific content object.
 * This simplifies key management by allowing the same master key to be
 * used for families of related content (e.g. all the versions of a file,
 * all of the intrinsic metadata associated with that file, etc.), without
 * having to manage an additional separately wrapped master key. It allows
 * hierarchically delegated access control (i.e. when the children of a node
 * inherit the permissions associated with that node) to proceed without 
 * requiring additional keys. It also acts to prevent errors, by limiting
 * the risk of IV/counter reuse, both by distributing encryption across
 * many derived keys, and deriving the IV/counter seeds automatically for
 * programmers.
 * 
 * NOTE: This is a low-level cryptographic API.
 * This class is used internally by CCN's built in access control prototype,
 * and may be used for key derivation in general, for people building other
 * encryption models for CCN. However, if you don't
 * already know what "key derivation" means, you should NOT be using it --
 * there is probably a different API that more closely fits your needs.
 * 
 * For each block, we compute the PRF using key K over
 * 		(counter || label || 0x00 || context || L)
 * where L is the desired output length in bits. 
 * 
 * We encode the counter and L both in 32 bit fields. The counter is initialized
 * to 1.
 * 
 * The KDF implemented herein is from NIST special publication 108-008,
 * and is described here:
 * http://twiki.parc.xerox.com/twiki/bin/view/CCN/KeyDerivation
 * 
 * @author smetters
 *
 */
public class KeyDerivationFunction {

	/**
	 * MAC algorithm used as PRF.
	 * We use HMAC-SHA256 as our primary PRF, because it is the most commonly
	 * implemented acceptable option. CMAC would potentially be preferable,
	 * but is not universally availalble yet.
	 */
	protected static final String MAC_ALGORITHM = "HmacSHA256";
	protected static final String EMPTY = "";

	/**
	 * Default parameterization of KDF for standard algorithm type. This is the
	 * routine that will be typically used by code that does not want to override
	 * default algorithms.
	 * @param contentName Name of this specific object, including the version (but not including
	 *     segment information).
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public static final ContentKeys DeriveKeysForObject(
			byte [] masterKeyBytes, 
			String label,
			ContentName contentName, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, XMLStreamException {
		byte [][] keyiv = DeriveKeysForObject(masterKeyBytes, ContentKeys.DEFAULT_AES_KEY_LENGTH*8, ContentKeys.IV_MASTER_LENGTH*8,
				label, contentName, publisher);
		return new ContentKeys(keyiv[0], keyiv[1]);
	}
	
	/**
	 * Used to derive keys for nodes in a name hierarchy. The key must be independent of
	 * publisher, as it is used to derive keys for intermediate nodes. As this is used as
	 * input to another key derivation call, no IV is derived.
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public static final byte [] DeriveKeyForNode(
			byte [] parentNodeKeyBytes,
			String label,
			ContentName nodeName) throws InvalidKeyException, XMLStreamException {
		return DeriveKeyForNode(parentNodeKeyBytes, ContentKeys.DEFAULT_AES_KEY_LENGTH*8, label, nodeName);
	}
	
	/**
	 * Hierarchically derive keys for a child node, given an ancestor key. Why not do this in 
	 * one step, with no intervening keys? This way we can delegate/install backlinks to keys
	 * in the middle of the hierarchy and things continue to work.
	 * @param ancestorNodeName the node with whom ancestorNodeKeyBytes is associated.
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public static final byte [] DeriveKeyForNode(
			ContentName ancestorNodeName, 
			byte [] ancestorNodeKey,
			String label,
			ContentName nodeName) throws InvalidKeyException, XMLStreamException {
		
		if ((null == ancestorNodeName) || (null == ancestorNodeKey) || (null == nodeName)) {
			throw new IllegalArgumentException("Names and keys cannot be null!");
		}
		if (!ancestorNodeName.isPrefixOf(nodeName)) {
			throw new IllegalArgumentException("Ancestor node name must be prefix of node name!");
		}
		if (ancestorNodeName.equals(nodeName)) {
			Library.logger().info("We're at the correct node already, will return the original node key.");
		}
		
		ContentName descendantNodeName = ancestorNodeName;
		byte [] descendantNodeKey = ancestorNodeKey;
		while (!descendantNodeName.equals(nodeName)) {
			descendantNodeName = nodeName.copy(descendantNodeName.count() + 1);
			descendantNodeKey = DeriveKeyForNode(descendantNodeKey, label, descendantNodeName);
		}
		return descendantNodeKey;
	}

	/**
	 * Parameterization of algorithm that returns a key and IV. Requested bit lengths must
	 * be divisible by 8.
	 * @param masterKeyBytes
	 * @param label
	 * @param name
	 * @param publisher
	 * @return returns an array of {key, iv/counter seed} suitable for use in segment encryption
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public static final byte [][] DeriveKeysForObject(
			byte [] masterKeyBytes, 
			int keyBitLength, int ivBitLength,
			String label,
			ContentName contentName, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, XMLStreamException {
		byte [] key = new byte[keyBitLength/8];
		byte [] iv = new byte[ivBitLength/8];

		byte [] keyandiv = DeriveKeyForObject(masterKeyBytes, 
				keyBitLength + ivBitLength,
				label, contentName, publisher);

		System.arraycopy(keyandiv, 0, key, 0, keyBitLength/8);
		System.arraycopy(keyandiv, keyBitLength/8, iv, 0, ivBitLength/8);
		return new byte [][]{key, iv};
	}


	/**
	 * Parameterization of KDF to use standard objects for context
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public static final byte [] DeriveKeyForObject(
			byte [] masterKeyBytes, int outputLengthInBits, 
			String label, 
			ContentName contentName, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, XMLStreamException {
		if ((null == contentName) || (null == publisher)) {
			throw new IllegalArgumentException("Content name and publisher cannot be null!");
		}
		return DeriveKey(masterKeyBytes, outputLengthInBits, label, 
				new XMLEncodable[]{contentName, publisher});
	}
	
	public static final byte [] DeriveKeyForNode(
			byte [] masterKeyBytes, int outputLengthInBits,
			String label, ContentName nodeName) throws InvalidKeyException, XMLStreamException {
		if (null == nodeName) {
			throw new IllegalArgumentException("Content name cannot be null!");			
		}
		return DeriveKey(masterKeyBytes, outputLengthInBits, label, new XMLEncodable[]{nodeName});
	}

	/**
	 * Master function building generic key derivation mechanism.
	 * @param masterKeyBytes
	 * @param outputLengthInBits
	 * @param label
	 * @param contextObjects
	 * @return
	 * @throws InvalidKeyException
	 * @throws XMLStreamException
	 */
	public static final byte [] DeriveKey(byte [] masterKeyBytes, int outputLengthInBits, 
			String label, XMLEncodable [] contextObjects) throws InvalidKeyException, XMLStreamException {

		if ((null == masterKeyBytes) || (masterKeyBytes.length == 0)) {
			throw new IllegalArgumentException("Master key bytes cannot be null or empty!");
		}
		// DKS deep debugging
		boolean allzeros = true;
		for (byte b : masterKeyBytes) {
			if (b != 0) {
				allzeros = false;
				break;
			}
		}
		if (allzeros) {
			Library.logger().warning("Warning: DeriveKey called with all 0's key of length " + masterKeyBytes.length);
		}
		Mac hmac;
		try {
			hmac = Mac.getInstance("HmacSHA256");
		} catch (NoSuchAlgorithmException e1) {
			Library.logger().severe("No HMAC-SHA256 available! Serious configuration issue!");
			throw new RuntimeException("No HMAC-SHA256 available! Serious configuration issue!");
		}
		hmac.init(new SecretKeySpec(masterKeyBytes, hmac.getAlgorithm()));
		
		// Precompute data used from block to block.
		byte [] Lbytes = new byte[] { (byte)(outputLengthInBits>>24), (byte)(outputLengthInBits>>16), 
				(byte)(outputLengthInBits>>8), (byte)outputLengthInBits };
		byte [][] contextBytes = new byte[((null == contextObjects) ? 0 : contextObjects.length)][];
		for (int j = 0; j < contextBytes.length; ++j) {
			contextBytes[j] = contextObjects[j].encode();
		}
		int outputLengthInBytes = (int)Math.ceil(outputLengthInBits/(1.0 * 8));
		byte [] outputBytes = new byte[outputLengthInBytes];

		// Number of rounds
		int n = (int)Math.ceil(outputLengthInBytes/(1.0 * hmac.getMacLength()));
		if (n < 1) {
			Library.logger().warning("Unexpected: 0 block key derivation: want " + outputLengthInBits + 
					" bits (" + outputLengthInBytes + " bytes).");
		}

		// Run the HMAC for enough blocks to get the keying material we need.
		for (int i=1; i <= n; ++i) {
			try {
				// 32-bit representation of i
				hmac.update(new byte[] { (byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte)i});
				// UTF-8 representation of label, including null terminator, or "" if empty
				if (null == label)
					label = EMPTY;
				try {
					hmac.update(label.getBytes("UTF-8"));
				} catch (UnsupportedEncodingException e) {
					Library.logger().severe("No UTF-8 encoding available! Serious configuration issue!");
					throw new RuntimeException("No UTF-8 encoding available! Serious configuration issue!");
				}
				// a 0 byte
				hmac.update((byte)0x00);
				// encoded context objects
				for (int k=0; k < contextBytes.length; ++k) {
					hmac.update(contextBytes[k]);
				}
				// 32-bit representation of L
				hmac.update(Lbytes);

				if (i < n) {
					hmac.doFinal(outputBytes, (i-1)*hmac.getMacLength());
				} else {
					byte [] finalBlock = hmac.doFinal();
					System.arraycopy(finalBlock, 0, outputBytes, (i-1)*hmac.getMacLength(), outputBytes.length - (i-1)*hmac.getMacLength());
				}
			} catch (IllegalStateException ex) {
				Library.logger().severe("Unexpected IllegalStateException in DeriveKey: hmac should have been initialized!");
				Library.warningStackTrace(ex);
				throw new RuntimeException("Unexpected IllegalStateException in DeriveKey: hmac should have been initialized!");
			} catch (ShortBufferException sx) {
				Library.logger().severe("Unexpected ShortBufferException in DeriveKey: buffer should be sufficient!");
				Library.warningStackTrace(sx);
				throw new RuntimeException("Unexpected ShortBufferException in DeriveKey: buffer should be sufficient!");
			}
		}
		return outputBytes;
	}
}