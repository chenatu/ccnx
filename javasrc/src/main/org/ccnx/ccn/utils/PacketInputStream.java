package org.ccnx.ccn.utils;

import java.io.IOException;
import java.io.InputStream;

public class PacketInputStream extends InputStream {
	protected int packetLength;
	protected int packetCount;
	protected int totalLength;
	protected int ptr = 0;
	protected byte bit = 0;

	public PacketInputStream(int packetLength, int packetCount) {
		this.packetLength = packetLength;
		this.packetCount = packetCount;
		this.totalLength = packetLength*packetCount;
	}

	@Override
	public int read() throws IOException {
		if (ptr < totalLength) {
			ptr++;
			return bit;
		}
		else
			return -1;
	}
}