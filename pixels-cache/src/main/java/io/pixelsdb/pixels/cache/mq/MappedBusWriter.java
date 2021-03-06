/*
 * This file is copied from MappedBus:
 *
 *   Copyright 2015 Caplogic AB.
 *   Licensed under the Apache License, Version 2.0.
 */
package io.pixelsdb.pixels.cache.mq;

import io.pixelsdb.pixels.cache.MemoryMappedFile;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;

/**
 * Class for writing messages to the bus.
 * <p>
 * Messages can either be message based or byte array based.
 * <p>
 * The typical usage is as follows:
 * <pre>
 * {@code
 * // Construct a writer
 * MappedBusWriter writer = new MappedBusWriter("/tmp/test", 100000L, 32, true);
 * writer.open();
 *
 * // A: write an object based message
 * PriceUpdate priceUpdate = new PriceUpdate();
 *
 * writer.write(priceUpdate);
 *
 * // B: write a byte array based message
 * byte[] buffer = new byte[32];
 *
 * writer.write(buffer, 0, buffer.length);
 *
 * // Close the writer
 * writer.close();
 * }
 * </pre>
 */
public class MappedBusWriter
{

    private MemoryMappedFile mem;

    private final String fileName;

    private final long fileSize;

    private final int entrySize;

    private final boolean append;

    /**
     * Constructs a new writer.
     *
     * @param fileName   the name of the memory mapped file
     * @param fileSize   the maximum size of the file
     * @param recordSize the maximum size of a record (excluding status flags and meta data)
     * @param append     whether to append to the file (will create a new file if false)
     */
    public MappedBusWriter(String fileName, long fileSize, int recordSize, boolean append)
    {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.entrySize = recordSize + MappedBusConstants.Length.RecordHeader;
        this.append = append;
    }

    /**
     * Opens the writer.
     *
     * @throws IOException if there was an error opening the file
     */
    public void open()
            throws IOException
    {
        if (!append)
        {
            new File(fileName).delete();
        }
        try
        {
            mem = new MemoryMappedFile(fileName, fileSize);
        }
        catch (Exception e)
        {
            throw new IOException("Unable to open the file: " + fileName, e);
        }
        if (append)
        {
            mem.compareAndSwapLong(MappedBusConstants.Structure.Limit, 0, MappedBusConstants.Structure.Data);
        }
        else
        {
            mem.putLongVolatile(MappedBusConstants.Structure.Limit, MappedBusConstants.Structure.Data);
        }
    }

    /**
     * Writes a message.
     *
     * @param message the message object to write
     * @throws EOFException in case the end of the file was reached
     */
    public void write(MappedBusMessage message)
            throws EOFException
    {
        long limit = allocate();
        long commitPos = limit;
        limit += MappedBusConstants.Length.StatusFlags;
        mem.putInt(limit, message.type());
        limit += MappedBusConstants.Length.Metadata;
        message.write(mem, limit);
        commit(commitPos);
    }

    /**
     * Writes a buffer of data.
     *
     * @param src    the output buffer
     * @param offset the offset in the buffer of the first byte to write
     * @param length the length of the data
     * @throws EOFException in case the end of the file was reached
     */
    public void write(byte[] src, int offset, int length)
            throws EOFException
    {
        long limit = allocate();
        long commitPos = limit;
        limit += MappedBusConstants.Length.StatusFlags;
        mem.putInt(limit, length);
        limit += MappedBusConstants.Length.Metadata;
        mem.setBytes(limit, src, offset, length);
        commit(commitPos);
    }

    private long allocate()
            throws EOFException
    {
        long limit = mem.getAndAddLong(MappedBusConstants.Structure.Limit, entrySize);
        if (limit + entrySize > fileSize)
        {
            throw new EOFException("End of file was reached");
        }
        return limit;
    }

    private void commit(long commitPos)
    {
        mem.putByteVolatile(commitPos, MappedBusConstants.Commit.Set);
    }

    /**
     * Closes the writer.
     *
     * @throws IOException if there was an error closing the file
     */
    public void close()
            throws IOException
    {
        try
        {
            mem.unmap();
        }
        catch (Exception e)
        {
            throw new IOException("Unable to close the file", e);
        }
    }
}