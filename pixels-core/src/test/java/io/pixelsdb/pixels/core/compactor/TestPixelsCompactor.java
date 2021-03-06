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
package io.pixelsdb.pixels.core.compactor;

import io.pixelsdb.pixels.common.exception.MetadataException;
import io.pixelsdb.pixels.common.metadata.MetadataService;
import io.pixelsdb.pixels.common.metadata.domain.Compact;
import io.pixelsdb.pixels.common.metadata.domain.Layout;
import io.pixelsdb.pixels.common.utils.DateUtil;
import io.pixelsdb.pixels.core.PixelsReader;
import io.pixelsdb.pixels.core.PixelsReaderImpl;
import io.pixelsdb.pixels.core.reader.PixelsReaderOption;
import io.pixelsdb.pixels.core.reader.PixelsRecordReader;
import io.pixelsdb.pixels.core.vector.BinaryColumnVector;
import io.pixelsdb.pixels.core.vector.LongColumnVector;
import io.pixelsdb.pixels.core.vector.VectorizedRowBatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class TestPixelsCompactor
{
    @SuppressWarnings("Duplicates")
    @Test
    public void testBasicCompact()
            throws MetadataException, IOException
    {
        Configuration conf = new Configuration();
        conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());

        // get compact layout
        MetadataService metadataService = new MetadataService("dbiir01", 18888);
        List<Layout> layouts = metadataService.getLayouts("pixels", "test_105");
        System.out.println("existing number of layouts: " + layouts.size());
        Layout layout = layouts.get(0);
        Compact compact = layout.getCompactObject();
        int rowGroupNum = compact.getNumRowGroupInBlock();
        int colNum = compact.getNumColumn();
        CompactLayout compactLayout = new CompactLayout(rowGroupNum, colNum);
        for (int i = 0; i < rowGroupNum; i++)
        {
            for (int j = 0; j < colNum; j++)
            {
                compactLayout.append(i, j);
            }
        }

        // get input file paths
        FileSystem fs = FileSystem.get(URI.create("hdfs://dbiir01:9000/"), conf);
        FileStatus[] statuses = fs.listStatus(
                new Path("hdfs://dbiir01:9000/pixels/pixels/test_105/v_0_order"));

        // compact
        int NO = 0;
        for (int i = 0; i < statuses.length; i += 16)
        {
            List<Path> sourcePaths = new ArrayList<>();
            for (int j = 0; j < 16; ++j)
            {
                //System.out.println(statuses[i+j].getPath().toString());
                sourcePaths.add(statuses[i + j].getPath());
            }
            long start = System.currentTimeMillis();

            String filePath = "hdfs://dbiir01:9000/pixels/pixels/test_105/v_0_compact/" +
                    NO + "_" +
                    DateUtil.getCurTime() +
                    ".compact.pxl";
            PixelsCompactor pixelsCompactor =
                    PixelsCompactor.newBuilder()
                            .setSourcePaths(sourcePaths)
                            .setCompactLayout(compactLayout)
                            .setFS(fs)
                            .setFilePath(new Path(filePath))
                            .setBlockSize(2L * 1024 * 1024 * 1024)
                            .setReplication((short) 2)
                            .setBlockPadding(false)
                            .build();
            pixelsCompactor.compact();
            pixelsCompactor.close();

            NO++;

            System.out.println(((System.currentTimeMillis() - start) / 1000.0) + " s for [" + filePath + "]");
        }
    }

    @SuppressWarnings("Duplicates")
    @Test
    public void testRealCompact()
            throws MetadataException, IOException
    {
        // get compact layout
        MetadataService metadataService = new MetadataService("dbiir01", 18888);
        List<Layout> layouts = metadataService.getLayouts("pixels", "test_105");
        System.out.println("existing number of layouts: " + layouts.size());
        Layout layout = null;
        int layoutId = 1;
        for (Layout layout1 : layouts)
        {
            if (layout1.getId() == layoutId)
            {
                layout = layout1;
                break;
            }
        }
        Compact compact = layout.getCompactObject();
        CompactLayout compactLayout = CompactLayout.fromCompact(compact);

        // get input file paths
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(URI.create("hdfs://dbiir01:9000/"), conf);
        FileStatus[] statuses = fs.listStatus(
                new Path("hdfs://dbiir01:9000/pixels/pixels/test_105/v_" + layout.getVersion() + "_order"));

        // compact
        int NO = 0;
        for (int i = 0; i + compact.getNumRowGroupInBlock() < statuses.length; i += compact.getNumRowGroupInBlock())
        {
            List<Path> sourcePaths = new ArrayList<>();
            for (int j = 0; j < compact.getNumRowGroupInBlock(); ++j)
            {
                //System.out.println(statuses[i+j].getPath().toString());
                sourcePaths.add(statuses[i + j].getPath());
            }

            long start = System.currentTimeMillis();

            String filePath = "hdfs://dbiir01:9000/pixels/pixels/test_105/v_" + layout.getVersion() + "_compact/" +
                    NO + "_" +
                    DateUtil.getCurTime() +
                    ".compact.pxl";
            PixelsCompactor pixelsCompactor =
                    PixelsCompactor.newBuilder()
                            .setSourcePaths(sourcePaths)
                            .setCompactLayout(compactLayout)
                            .setFS(fs)
                            .setFilePath(new Path(filePath))
                            .setBlockSize(2L * 1024 * 1024 * 1024)
                            .setReplication((short) 2)
                            .setBlockPadding(false)
                            .build();
            pixelsCompactor.compact();
            pixelsCompactor.close();

            NO++;

            System.out.println(((System.currentTimeMillis() - start) / 1000.0) + " s for [" + filePath + "]");
        }
    }

    @Test
    public void testContent()
            throws IOException
    {
        String filePath = "hdfs://presto00:9000/pixels/pixels/testnull_pixels/compact.3.pxl";
        //String filePath = "hdfs://presto00:9000/pixels/testNull_pixels/201806190954180.pxl";
        Path path = new Path(filePath);
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(URI.create(filePath), conf);
        PixelsReader reader = PixelsReaderImpl.newBuilder()
                .setFS(fs)
                .setPath(path)
                .build();

        PixelsReaderOption option = new PixelsReaderOption();
        String[] cols = {"Domain", "SamplePercent"};
        option.skipCorruptRecords(true);
        option.tolerantSchemaEvolution(true);
        option.includeCols(cols);

        VectorizedRowBatch rowBatch;
        PixelsRecordReader recordReader = reader.read(option);

        while (true)
        {
            rowBatch = recordReader.readBatch(1000);
            LongColumnVector acv = (LongColumnVector) rowBatch.cols[1];
            BinaryColumnVector zcv = (BinaryColumnVector) rowBatch.cols[0];
            for (int i = 0; i < acv.vector.length; ++i)
            {
                System.out.println(acv.vector[i]);
            }
            if (rowBatch.endOfFile)
            {
                break;
            }

        }
    }
}
