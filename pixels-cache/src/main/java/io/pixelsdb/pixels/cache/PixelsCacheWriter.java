/*
 * Copyright 2019 PixelsDB.
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
package io.pixelsdb.pixels.cache;

import com.coreos.jetcd.data.KeyValue;
import io.pixelsdb.pixels.common.exception.FSException;
import io.pixelsdb.pixels.common.metadata.domain.Compact;
import io.pixelsdb.pixels.common.metadata.domain.Layout;
import io.pixelsdb.pixels.common.physical.*;
import io.pixelsdb.pixels.common.utils.ConfigFactory;
import io.pixelsdb.pixels.common.utils.Constants;
import io.pixelsdb.pixels.common.utils.EtcdUtil;
import io.pixelsdb.pixels.core.PixelsProto;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * pixels cache writer
 *
 * @author guodong
 * @author hank
 */
public class PixelsCacheWriter
{
    private final static short READABLE = 0;
    private final static Logger logger = LogManager.getLogger(PixelsCacheWriter.class);

    private final MemoryMappedFile cacheFile;
    private final MemoryMappedFile indexFile;
    private final FileSystem fs;
    private final PixelsRadix radix;
    private final EtcdUtil etcdUtil;
    private final String host;
    private long currentIndexOffset;
    private long allocatedIndexOffset = PixelsCacheUtil.INDEX_RADIX_OFFSET;
    private long cacheOffset = 0L;  // this is used in the write() method.
    private ByteBuffer nodeBuffer = ByteBuffer.allocate(8 * 256);
    private ByteBuffer cacheIdxBuffer = ByteBuffer.allocate(PixelsCacheIdx.SIZE);

    private PixelsCacheWriter(MemoryMappedFile cacheFile,
                              MemoryMappedFile indexFile,
                              FileSystem fs,
                              PixelsRadix radix,
                              EtcdUtil etcdUtil,
                              String host)
    {
        this.cacheFile = cacheFile;
        this.indexFile = indexFile;
        this.fs = fs;
        this.radix = radix;
        this.etcdUtil = etcdUtil;
        this.host = host;
        this.nodeBuffer.order(ByteOrder.BIG_ENDIAN);
    }

    public static class Builder
    {
        private String builderCacheLocation = "";
        private long builderCacheSize;
        private String builderIndexLocation = "";
        private long builderIndexSize;
        private FileSystem builderFS;
        private boolean builderOverwrite = true;
        private String builderHostName = null;

        private Builder()
        {
        }

        public PixelsCacheWriter.Builder setCacheLocation(String cacheLocation)
        {
            checkArgument(!cacheLocation.isEmpty(), "location should bot be empty");
            this.builderCacheLocation = cacheLocation;

            return this;
        }

        public PixelsCacheWriter.Builder setCacheSize(long cacheSize)
        {
            checkArgument(cacheSize > 0, "size should be positive");
            this.builderCacheSize = cacheSize;

            return this;
        }

        public PixelsCacheWriter.Builder setIndexLocation(String location)
        {
            checkArgument(!location.isEmpty(), "index location should not be empty");
            this.builderIndexLocation = location;

            return this;
        }

        public PixelsCacheWriter.Builder setIndexSize(long size)
        {
            checkArgument(size > 0, "index size should be positive");
            this.builderIndexSize = size;

            return this;
        }

        public PixelsCacheWriter.Builder setFS(FileSystem fs)
        {
            checkArgument(fs != null, "fs should not be null");
            this.builderFS = fs;

            return this;
        }

        public PixelsCacheWriter.Builder setOverwrite(boolean overwrite)
        {
            this.builderOverwrite = overwrite;
            return this;
        }

        public PixelsCacheWriter.Builder setHostName(String hostName)
        {
            this.builderHostName = hostName;
            return this;
        }

        public PixelsCacheWriter build()
                throws Exception
        {
            MemoryMappedFile cacheFile = new MemoryMappedFile(builderCacheLocation, builderCacheSize);
            MemoryMappedFile indexFile = new MemoryMappedFile(builderIndexLocation, builderIndexSize);
            PixelsRadix radix;
            // check if cache and index exists.
            //   if overwrite is not true, and cache and index file already exists, reconstruct radix from existing index.
            if (!builderOverwrite && PixelsCacheUtil.checkMagic(indexFile) && PixelsCacheUtil.checkMagic(cacheFile))
            {
                radix = PixelsCacheUtil.getIndexRadix(indexFile);
            }
            //   else, create a new radix tree, and initialize the index and cache file.
            else
            {
                radix = new PixelsRadix();
                PixelsCacheUtil.initialize(indexFile, cacheFile);
            }
            EtcdUtil etcdUtil = EtcdUtil.Instance();

            return new PixelsCacheWriter(cacheFile, indexFile, builderFS, radix, etcdUtil, builderHostName);
        }
    }

    public static Builder newBuilder()
    {
        return new Builder();
    }

    public MemoryMappedFile getIndexFile()
    {
        return indexFile;
    }

    /**
     * Return code:
     * -1: update failed.
     * 0: no updates are needed or update successfully.
     * 2: update size exceeds the limit.
     */
    public int updateAll(int version, Layout layout)
    {
        try
        {
            // get the caching file list
            String key = Constants.CACHE_LOCATION_LITERAL + version + "_" + host;
            KeyValue keyValue = etcdUtil.getKeyValue(key);
            if (keyValue == null)
            {
                logger.debug("Found no allocated files. No updates are needed. " + key);
                return 0;
            }
            String fileStr = keyValue.getValue().toStringUtf8();
            String[] files = fileStr.split(";");
            return internalUpdate(version, layout, files);
        }
        catch (IOException | FSException e)
        {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Currently, this is an interface for unit tests.
     * This method only updates index content and cache content (without touching headers)
     */
    public void write(PixelsCacheKey key, byte[] value)
    {
        PixelsCacheIdx cacheIdx = new PixelsCacheIdx(cacheOffset, value.length);
        cacheFile.putBytes(cacheOffset, value);
        cacheOffset += value.length;
        radix.put(key.blockId, key.rowGroupId, key.columnId, cacheIdx);
    }

    /**
     * Currently, this is an interface for unit tests.
     */
    public void flush()
    {
        flushIndex();
    }

    private int internalUpdate(int version, Layout layout, String[] files)
            throws IOException, FSException
    {
        int status = 0;
        // get the new caching layout
        Compact compact = layout.getCompactObject();
        int cacheBorder = compact.getCacheBorder();
        List<String> cacheColumnletOrders = compact.getColumnletOrder().subList(0, cacheBorder);
        // set rwFlag as write
        logger.debug("Set index rwFlag as write");
        PixelsCacheUtil.setIndexRW(indexFile, PixelsCacheUtil.RWFlag.WRITE.getId());
        // update cache content
        radix.removeAll();
        long cacheOffset = 0L;
        boolean enableAbsoluteBalancer = Boolean.parseBoolean(
                ConfigFactory.Instance().getProperty("enable.absolute.balancer"));
        outer_loop:
        for (String file : files)
        {
            if (enableAbsoluteBalancer)
            {
                // this is used for experimental purpose only.
                // may be removed later.
                file = ensureLocality(file);
            }
            PixelsPhysicalReader pixelsPhysicalReader = new PixelsPhysicalReader(fs, new Path(file));
            int physicalLen;
            long physicalOffset;
            // update radix and cache content
            for (int i = 0; i < cacheColumnletOrders.size(); i++)
            {
                String[] columnletIdStr = cacheColumnletOrders.get(i).split(":");
                short rowGroupId = Short.parseShort(columnletIdStr[0]);
                short columnId = Short.parseShort(columnletIdStr[1]);
                PixelsProto.RowGroupFooter rowGroupFooter = pixelsPhysicalReader.readRowGroupFooter(rowGroupId);
                PixelsProto.ColumnChunkIndex chunkIndex =
                        rowGroupFooter.getRowGroupIndexEntry().getColumnChunkIndexEntries(columnId);
                physicalLen = (int) chunkIndex.getChunkLength();
                physicalOffset = chunkIndex.getChunkOffset();
                if (cacheOffset + physicalLen >= cacheFile.getSize())
                {
                    logger.debug("Cache writes have exceeded cache size. Break. Current size: " + cacheOffset);
                    status = 2;
                    break outer_loop;
                }
                else
                {
                    radix.put(pixelsPhysicalReader.getCurrentBlockId(), rowGroupId, columnId,
                            new PixelsCacheIdx(cacheOffset, physicalLen));
                    byte[] columnlet = pixelsPhysicalReader.read(physicalOffset, physicalLen);
                    cacheFile.putBytes(cacheOffset, columnlet);
                    logger.debug(
                            "Cache write: " + file + "-" + rowGroupId + "-" + columnId + ", offset: " + cacheOffset + ", length: " + columnlet.length);
                    cacheOffset += physicalLen;
                }
            }
        }
        logger.debug("Cache writer ends at offset: " + cacheOffset);
        // update cache version
        PixelsCacheUtil.setIndexVersion(indexFile, version);
        // flush index
        flushIndex();
        logger.debug("Cache index ends at offset: " + currentIndexOffset);
        // set rwFlag as readable
        PixelsCacheUtil.setIndexRW(indexFile, READABLE);
        return status;
    }

    /**
     * This method is currently used for experimental purpose.
     * @param path
     * @return
     */
    private String ensureLocality (String path)
    {
        String newPath = path.substring(0, path.indexOf(".pxl")) + "_" + host + ".pxl";
        String configDir = ConfigFactory.Instance().getProperty("hdfs.config.dir");
        try
        {
            FSFactory fsFactory = FSFactory.Instance(configDir);
            Path dfsPath = new Path(path);
            Path newDfsPath = new Path(newPath);
            String[] dataNodes = fsFactory.getBlockHosts(dfsPath, 0, Long.MAX_VALUE);
            boolean isLocal = false;
            for (String dataNode : dataNodes)
            {
                if (dataNode.equals(host))
                {
                    isLocal = true;
                    break;
                }
            }
            if (isLocal == false)
            {
                // file is not local, move it to local.
                FileSystem fs = fsFactory.getFileSystem().get();
                PhysicalReader reader = PhysicalReaderUtil.newPhysicalFSReader(fs, dfsPath);
                PhysicalWriter writer = PhysicalWriterUtil.newPhysicalFSWriter(fs, newDfsPath,
                        2048l*1024l*1024l, (short) 1, true);
                byte[] buffer = new byte[1024*1024*32]; // 32MB buffer for copy.
                long copiedBytes = 0l, fileLength = reader.getFileLength();
                boolean success = true;
                try
                {
                    while (copiedBytes < fileLength)
                    {
                        int bytesToCopy = 0;
                        if (copiedBytes + buffer.length <= fileLength)
                        {
                            bytesToCopy = buffer.length;
                        } else
                        {
                            bytesToCopy = (int) (fileLength - copiedBytes);
                        }
                        reader.readFully(buffer, 0, bytesToCopy);
                        writer.prepare(bytesToCopy);
                        writer.append(buffer, 0, bytesToCopy);
                        copiedBytes += bytesToCopy;
                    }
                    reader.close();
                    writer.flush();
                    writer.close();
                } catch (IOException e)
                {
                    logger.error("failed to copy file", e);
                    success = false;
                }
                if (success)
                {
                    fs.delete(dfsPath, false);
                    return newPath;
                }
                else
                {
                    fs.delete(newDfsPath, false);
                    return path;
                }

            } else
            {
                return path;
            }
        } catch (FSException e)
        {
            logger.error("failed to instance FSFactory", e);
        } catch (IOException e)
        {
            logger.error("failed to delete file", e);
        }

        return null;
    }

    /**
     * Traverse radix to get all cached values, and put them into cacheColumnlets list.
     */
    private void traverseRadix(List<ColumnletId> cacheColumnlets)
    {
        RadixNode root = radix.getRoot();
        if (root.getSize() == 0)
        {
            return;
        }
        visitRadix(cacheColumnlets, root);
    }

    /**
     * Visit radix recursively in depth first way.
     * Maybe considering using a stack to store edge values along the visitation path.
     * Push edges in as going deeper, and pop out as going shallower.
     */
    private void visitRadix(List<ColumnletId> cacheColumnlets, RadixNode node)
    {
        if (node.isKey())
        {
            PixelsCacheIdx value = node.getValue();
            ColumnletId columnletId = new ColumnletId();
            columnletId.cacheOffset = value.offset;
            columnletId.cacheLength = value.length;
            cacheColumnlets.add(columnletId);
        }
        for (RadixNode n : node.getChildren().values())
        {
            visitRadix(cacheColumnlets, n);
        }
    }

    /**
     * Write radix tree node.
     */
    private void writeRadix(RadixNode node)
    {
        if (flushNode(node))
        {
            for (RadixNode n : node.getChildren().values())
            {
                writeRadix(n);
            }
        }
    }

    /**
     * Flush node content to the index file based on {@code currentIndexOffset}.
     * Header(4 bytes) + [Child(8 bytes)]{n} + edge(variable size) + value(optional).
     * Header: isKey(1 bit) + edgeSize(22 bits) + childrenSize(9 bits)
     * Child: leader(1 byte) + child_offset(7 bytes)
     */
    private boolean flushNode(RadixNode node)
    {
        nodeBuffer.clear();
        if (currentIndexOffset >= indexFile.getSize())
        {
            logger.debug("Index file have exceeded cache size. Break. Current size: " + currentIndexOffset);
            return false;
        }
        if (node.offset == 0)
        {
            node.offset = currentIndexOffset;
        }
        else
        {
            currentIndexOffset = node.offset;
        }
        allocatedIndexOffset += node.getLengthInBytes();
        int header = 0;
        int edgeSize = node.getEdge().length;
        header = header | (edgeSize << 9);
        int isKeyMask = 1 << 31;
        if (node.isKey())
        {
            header = header | isKeyMask;
        }
        header = header | node.getChildren().size();
        indexFile.putInt(currentIndexOffset, header);  // header
        currentIndexOffset += 4;
        for (Byte key : node.getChildren().keySet())
        {   // children
            RadixNode n = node.getChild(key);
            int len = n.getLengthInBytes();
            n.offset = allocatedIndexOffset;
            allocatedIndexOffset += len;
            long childId = 0L;
            childId = childId | ((long) key << 56);  // leader
            childId = childId | n.offset;  // offset
            nodeBuffer.putLong(childId);
//            indexFile.putLong(currentIndexOffset, childId);
//            currentIndexOffset += 8;
        }
        byte[] nodeBytes = new byte[node.getChildren().size() * 8];
        nodeBuffer.flip();
        nodeBuffer.get(nodeBytes);
        indexFile.putBytes(currentIndexOffset, nodeBytes);
        currentIndexOffset += nodeBytes.length;
        indexFile.putBytes(currentIndexOffset, node.getEdge()); // edge
        currentIndexOffset += node.getEdge().length;
        if (node.isKey())
        {  // value
            node.getValue().getBytes(cacheIdxBuffer);
            indexFile.putBytes(currentIndexOffset, cacheIdxBuffer.array());
            currentIndexOffset += 12;
        }
        return true;
    }

    /**
     * Flush out index to index file from start.
     */
    private void flushIndex()
    {
        // set index content offset, skip the index header.
        currentIndexOffset = PixelsCacheUtil.INDEX_RADIX_OFFSET;
        // if root contains nodes, which means the tree is not empty,then write nodes.
        if (radix.getRoot().getSize() != 0)
        {
            writeRadix(radix.getRoot());
        }
    }

    public void close()
            throws Exception
    {
        indexFile.unmap();
        cacheFile.unmap();
    }
}
