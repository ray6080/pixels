/*
 * Copyright 2017-2019 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.core.reader;

import io.pixelsdb.pixels.core.PixelsProto;
import io.pixelsdb.pixels.core.TypeDescription;
import io.pixelsdb.pixels.core.encoding.RunLenIntDecoder;
import io.pixelsdb.pixels.core.utils.BitUtils;
import io.pixelsdb.pixels.core.utils.DynamicIntArray;
import io.pixelsdb.pixels.core.vector.BinaryColumnVector;
import io.pixelsdb.pixels.core.vector.ColumnVector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author guodong
 * @author hank
 */
public class StringColumnReader
        extends ColumnReader
{
    private int originsOffset;
    private int startsOffset;
    private ByteBuf inputBuffer = null; // TODO: change ByteBuf to java.nio.ByteBuffer
    private ByteBuf originsBuf = null;
    private int[] orders = null;
    private int[] starts = null;
    private byte[] isNull = new byte[8];
    private ByteBuf contentBuf = null;
    private RunLenIntDecoder lensDecoder = null;
    private RunLenIntDecoder contentDecoder = null;
    private int isNullOffset = 0;
    private int isNullBitIndex = 0;

    StringColumnReader(TypeDescription type)
    {
        super(type);
    }

    /**
     * Read values from input buffer.
     *
     * @param input    input buffer
     * @param encoding encoding type
     * @param size     number of values to read
     * @param vector   vector to read into
     * @throws IOException
     */
    @Override
    public void read(ByteBuffer input, PixelsProto.ColumnEncoding encoding,
                     int offset, int size, int pixelStride, final int vectorIndex,
                     ColumnVector vector, PixelsProto.ColumnChunkIndex chunkIndex)
            throws IOException
    {

        BinaryColumnVector columnVector = (BinaryColumnVector) vector;
        if (offset == 0)
        {
            if (inputBuffer != null)
            {
                inputBuffer.release();
            }
            if (input.isDirect())
            {
                // TODO: reduce memory copy.
                byte[] bytes = new byte[input.limit()];
                input.get(bytes);
                inputBuffer = Unpooled.wrappedBuffer(bytes);
            }
            else
            {
                inputBuffer = Unpooled.wrappedBuffer(input);
            }
            readContent(input.limit(), encoding);
            isNullOffset = (int) chunkIndex.getIsNullOffset();
            hasNull = true;
            elementIndex = 0;
            isNullBitIndex = 8;
        }
        // if dictionary encoded
        if (encoding.getKind().equals(PixelsProto.ColumnEncoding.Kind.DICTIONARY))
        {
            // read original bytes
            // we get bytes here to reduce memory copies and avoid creating many small byte arrays.
            byte[] buffer = originsBuf.array();
            // The available first byte in buffer should start from originsOffset.
            // bufferStart is the first byte within buffer.
            // DO NOT use originsOffset as bufferStart, as multiple input byte buffer read
            // from disk (not from pixels cache) may share the same backing array, each of them starts
            // from a different offset. originsOffset equals to originsBuf.arrayOffset() only when a
            // input buffer starts from the first byte of backing array.
            int bufferStart = originsBuf.arrayOffset();
            for (int i = 0; i < size; i++)
            {
                if (elementIndex % pixelStride == 0)
                {
                    int pixelId = elementIndex / pixelStride;
                    hasNull = chunkIndex.getPixelStatistics(pixelId).getStatistic().getHasNull();
                    if (hasNull && isNullBitIndex > 0)
                    {
                        BitUtils.bitWiseDeCompact(isNull, inputBuffer.array(), isNullOffset++, 1);
                        isNullBitIndex = 0;
                    }
                }
                if (hasNull && isNullBitIndex >= 8)
                {
                    BitUtils.bitWiseDeCompact(isNull, inputBuffer.array(), isNullOffset++, 1);
                    isNullBitIndex = 0;
                }
                if (hasNull && isNull[isNullBitIndex] == 1)
                {
                    columnVector.isNull[i + vectorIndex] = true;
                    columnVector.noNulls = false;
                }
                else
                {
                    int originId = orders[(int) contentDecoder.next()];
                    int tmpLen;
                    if (originId < starts.length - 1)
                    {
                        tmpLen = starts[originId + 1] - starts[originId];
                    }
                    else
                    {
                        tmpLen = startsOffset - originsOffset - starts[originId];
                    }
                    // use setRef instead of setVal to reduce memory copy.
                    columnVector.setRef(i + vectorIndex, buffer, bufferStart + starts[originId], tmpLen);
                }
                if (hasNull)
                {
                    isNullBitIndex++;
                }
                elementIndex++;
            }
        }
        // if un-encoded
        else
        {
            // read values
            // we get bytes here to reduce memory copies and avoid creating many small byte arrays.
            byte[] buffer = contentBuf.array();
            int bufferOffset = contentBuf.arrayOffset();
            for (int i = 0; i < size; i++)
            {
                if (elementIndex % pixelStride == 0)
                {
                    int pixelId = elementIndex / pixelStride;
                    hasNull = chunkIndex.getPixelStatistics(pixelId).getStatistic().getHasNull();
                    if (hasNull && isNullBitIndex > 0)
                    {
                        BitUtils.bitWiseDeCompact(isNull, inputBuffer.array(), isNullOffset++, 1);
                        isNullBitIndex = 0;
                    }
                }
                if (hasNull && isNullBitIndex >= 8)
                {
                    BitUtils.bitWiseDeCompact(isNull, inputBuffer.array(), isNullOffset++, 1);
                    isNullBitIndex = 0;
                }
                if (hasNull && isNull[isNullBitIndex] == 1)
                {
                    columnVector.isNull[i + vectorIndex] = true;
                    columnVector.noNulls = false;
                }
                else
                {
                    int len = (int) lensDecoder.next();
                    // use setRef instead of setVal to reduce memory copy.
                    columnVector.setRef(i + vectorIndex, buffer, bufferOffset, len);
                    bufferOffset += len;
                }
                if (hasNull)
                {
                    isNullBitIndex++;
                }
                elementIndex++;
            }
        }
    }

    private void readContent(int inputLength, PixelsProto.ColumnEncoding encoding)
            throws IOException
    {
        // TODO: reduce memory copy in this method.
        if (encoding.getKind().equals(PixelsProto.ColumnEncoding.Kind.DICTIONARY))
        {
            // read offsets
            inputBuffer.markReaderIndex();
            inputBuffer.skipBytes(inputLength - 3 * Integer.BYTES);
            originsOffset = inputBuffer.readInt();
            startsOffset = inputBuffer.readInt();
            int ordersOffset = inputBuffer.readInt();
            inputBuffer.resetReaderIndex();
            // read buffers
            contentBuf = inputBuffer.slice(0, originsOffset);
            originsBuf = inputBuffer.slice(originsOffset, startsOffset - originsOffset);
            ByteBuf startsBuf = inputBuffer.slice(startsOffset, ordersOffset - startsOffset);
            ByteBuf ordersBuf = inputBuffer.slice(ordersOffset, inputLength - ordersOffset);
            int originNum = 0;
            DynamicIntArray startsArray = new DynamicIntArray();
            RunLenIntDecoder startsDecoder = new RunLenIntDecoder(new ByteBufInputStream(startsBuf), false);
            while (startsDecoder.hasNext())
            {
                startsArray.add((int) startsDecoder.next());
                originNum++;
            }
            // read starts and orders
            RunLenIntDecoder ordersDecoder = new RunLenIntDecoder(new ByteBufInputStream(ordersBuf), false);
            starts = new int[originNum];
            orders = new int[originNum];
            for (int i = 0; i < originNum && ordersDecoder.hasNext(); i++)
            {
                starts[i] = startsArray.get(i);
                orders[i] = (int) ordersDecoder.next();
            }
            contentDecoder = new RunLenIntDecoder(new ByteBufInputStream(contentBuf), false);
        }
        else
        {
            // read lens field offset
            inputBuffer.markReaderIndex();
            inputBuffer.skipBytes(inputLength - Integer.BYTES);
            int lensOffset = inputBuffer.readInt();
            inputBuffer.resetReaderIndex();
            // read strings
            contentBuf = inputBuffer.slice(0, lensOffset);
            // read lens field
            ByteBuf lensBuf = inputBuffer.slice(lensOffset, inputLength - Integer.BYTES - lensOffset);
            lensDecoder = new RunLenIntDecoder(new ByteBufInputStream(lensBuf), false);
        }
    }
}
