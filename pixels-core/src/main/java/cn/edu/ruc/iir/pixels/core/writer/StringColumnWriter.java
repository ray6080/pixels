package cn.edu.ruc.iir.pixels.core.writer;

import cn.edu.ruc.iir.pixels.core.Constants;
import cn.edu.ruc.iir.pixels.core.PixelsProto;
import cn.edu.ruc.iir.pixels.core.TypeDescription;
import cn.edu.ruc.iir.pixels.core.encoding.RunLenIntEncoder;
import cn.edu.ruc.iir.pixels.core.utils.StringRedBlackTree;
import cn.edu.ruc.iir.pixels.core.vector.BytesColumnVector;
import cn.edu.ruc.iir.pixels.core.vector.ColumnVector;

import java.io.IOException;

/**
 * String column writer.
 *
 * The string column chunk consists of seven fields:
 * 1. pixels field (run length encoded pixels after dictionary encoding or un-encoded string values)
 * 2. origins field (distinct string bytes array)
 * 3. starts field (starting offsets indicating starting points of each string in the origins field)
 * 4. orders field (dumped orders array mapping dictionary encoded value to final sorted order)
 * 5. origins offset field (an integer value indicating offset of the origins field in the chunk)
 * 6. starts offset field (an integer value indicating offset of the starts field in the chunk)
 * 7. orders offset field (an integer value indicating offset of the orders field in the chunk)
 *
 * Pixels field is necessary in all cases.
 * Other fields only exist when dictionary encoding is enabled.
 *
 * @author guodong
 */
public class StringColumnWriter extends BaseColumnWriter
{
    private final long[] curPixelVector = new long[pixelStride];   // current vector holding encoded values of string
    private final StringRedBlackTree dictionary = new StringRedBlackTree(Constants.INIT_DICT_SIZE);
    private boolean useDictionaryEncoding;
    private boolean doneDictionaryEncodingCheck = false;

    public StringColumnWriter(TypeDescription schema, int pixelStride, boolean isEncoding)
    {
        super(schema, pixelStride, isEncoding);
        this.useDictionaryEncoding = isEncoding;
        encoder = new RunLenIntEncoder(false, true);
    }

    @Override
    public int write(ColumnVector vector, int size) throws IOException
    {
        BytesColumnVector columnVector = (BytesColumnVector) vector;
        byte[][] values = columnVector.vector;
        int[] vLens = columnVector.length;
        int[] vOffsets = columnVector.start;
        int curPartLength;
        int curPartOffset = 0;
        int nextPartLength = size;

        if (useDictionaryEncoding) {
            while ((curPixelEleCount + nextPartLength) >= pixelStride) {
                curPartLength = pixelStride - curPixelEleCount;
                for (int i = 0; i < curPartLength; i++) {
                    curPixelVector[curPixelEleCount + i] = dictionary.add(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                    pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
                }
                curPixelEleCount += curPartLength;
                newPixel();
                curPartOffset += curPartLength;
                nextPartLength = size - curPartOffset;
            }

            curPartLength = nextPartLength;
            for (int i = 0; i < curPartLength; i++) {
                curPixelVector[curPixelEleCount + i] = dictionary.add(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
            }
            curPixelEleCount += curPartLength;
            curPartOffset += curPartLength;
            nextPartLength = size - curPartOffset;

            // this should never be reached.
            if (nextPartLength > 0) {
                curPartLength = nextPartLength;
                for (int i = 0; i < curPartLength; i++) {
                    curPixelVector[curPixelEleCount + i] = dictionary.add(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                    pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
                }
                curPixelEleCount += nextPartLength;
            }
        }
        else {
            // directly add to outputStream if not using dictionary encoding
            while ((curPixelEleCount + nextPartLength) >= pixelStride) {
                curPartLength = pixelStride - curPixelEleCount;
                for (int i = 0; i < curPartLength; i++) {
                    outputStream.write(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                    pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
                }
                curPixelEleCount += curPartLength;
                newPixel();
                curPartOffset += curPartLength;
                nextPartLength = size - curPartOffset;
            }

            curPartLength = nextPartLength;
            for (int i = 0; i < curPartLength; i++) {
                outputStream.write(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
            }
            curPixelEleCount += curPartLength;
            curPartOffset += curPartLength;
            nextPartLength = size - curPartOffset;

            // this should never be reached.
            if (nextPartLength > 0) {
                curPartLength = nextPartLength;
                for (int i = 0; i < curPartLength; i++) {
                    outputStream.write(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i]);
                    pixelStatRecorder.updateString(values[curPartOffset + i], vOffsets[curPartOffset + i], vLens[curPartOffset + i], 1);
                }
                curPixelEleCount += nextPartLength;
            }
        }
        return outputStream.size();
    }

    @Override
    public void newPixel() throws IOException
    {
        if (useDictionaryEncoding) {
            // for dictionary encoding. run length encode again.
            outputStream.write(encoder.encode(curPixelVector));
        }
        // else ignore outputStream

        // merge and reset
        curPixelPosition = outputStream.size();
        curPixelEleCount = 0;
        columnChunkStatRecorder.merge(pixelStatRecorder);
        PixelsProto.PixelStatistic.Builder pixelStat =
                PixelsProto.PixelStatistic.newBuilder();
        pixelStat.setStatistic(pixelStatRecorder.serialize());
        columnChunkIndex.addPixelPositions(lastPixelPosition);
        columnChunkIndex.addPixelStatistics(pixelStat.build());
        lastPixelPosition = curPixelPosition;
        pixelStatRecorder.reset();
    }

    @Override
    public void flush() throws IOException
    {
        // flush out pixels field
        super.flush();
        // check if continue using dictionary encoding or not in the coming chunks
        if (!doneDictionaryEncodingCheck) {
            checkDictionaryEncoding();
        }
        // flush out other fields
        if (useDictionaryEncoding) {
            flushDictionary();
        }
    }

    private void flushDictionary() throws IOException
    {
        int originsFieldOffset;
        int startsFieldOffset;
        int ordersFieldOffset;
        int size = dictionary.size();
        int[] starts = new int[size];
        int[] orders = new int[size];

        originsFieldOffset = outputStream.size();

        // recursively visit the red black tree, and fill origins field, get starts array and orders array
        dictionary.visit(new StringRedBlackTree.Visitor()
        {
            private int initStart = 0;
            private int currentId = 0;

            @Override
            public void visit(StringRedBlackTree.VisitorContext context) throws IOException
            {
                context.writeBytes(outputStream);
                starts[currentId] = initStart;
                initStart += context.getLength();
                orders[context.getOriginalPosition()] = currentId++;
            }
        });

        startsFieldOffset = outputStream.size();

        for (int i = 0; i < size; i++)
        {
            outputStream.write(starts[i]);
        }

        ordersFieldOffset = outputStream.size();

        for (int i = 0; i < size; i++)
        {
            outputStream.write(orders[i]);
        }

        outputStream.write(originsFieldOffset);
        outputStream.write(startsFieldOffset);
        outputStream.write(ordersFieldOffset);
    }

    private void checkDictionaryEncoding()
    {
        int valueNum = outputStream.size() / Integer.BYTES;
        float ratio = valueNum > 0 ? (float) dictionary.size() / valueNum : 0.0f;
        useDictionaryEncoding = ratio <= Constants.DICT_KEY_SIZE_THRESHOLD;
        doneDictionaryEncodingCheck = true;
    }
}