/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.carbondata.datamap.bloom;

import java.io.IOException;
import java.util.*;

import org.apache.carbondata.common.annotations.InterfaceAudience;
import org.apache.carbondata.common.exceptions.sql.MalformedDataMapCommandException;
import org.apache.carbondata.common.logging.LogService;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.cache.Cache;
import org.apache.carbondata.core.cache.CacheProvider;
import org.apache.carbondata.core.cache.CacheType;
import org.apache.carbondata.core.constants.CarbonV3DataFormatConstants;
import org.apache.carbondata.core.datamap.DataMapDistributable;
import org.apache.carbondata.core.datamap.DataMapLevel;
import org.apache.carbondata.core.datamap.DataMapMeta;
import org.apache.carbondata.core.datamap.DataMapStoreManager;
import org.apache.carbondata.core.datamap.Segment;
import org.apache.carbondata.core.datamap.TableDataMap;
import org.apache.carbondata.core.datamap.dev.DataMapBuilder;
import org.apache.carbondata.core.datamap.dev.DataMapFactory;
import org.apache.carbondata.core.datamap.dev.DataMapWriter;
import org.apache.carbondata.core.datamap.dev.cgdatamap.CoarseGrainDataMap;
import org.apache.carbondata.core.datastore.block.SegmentProperties;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.filesystem.CarbonFileFilter;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.features.TableOperation;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.metadata.schema.table.DataMapSchema;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.scan.filter.intf.ExpressionType;
import org.apache.carbondata.core.statusmanager.SegmentStatusManager;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.path.CarbonTablePath;
import org.apache.carbondata.events.Event;

import org.apache.commons.lang3.StringUtils;

/**
 * This class is for Bloom Filter for blocklet level
 */
@InterfaceAudience.Internal
public class BloomCoarseGrainDataMapFactory extends DataMapFactory<CoarseGrainDataMap> {
  private static final LogService LOGGER = LogServiceFactory.getLogService(
      BloomCoarseGrainDataMapFactory.class.getName());
  /**
   * property for size of bloom filter
   */
  private static final String BLOOM_SIZE = "bloom_size";
  /**
   * default size for bloom filter, cardinality of the column.
   */
  private static final int DEFAULT_BLOOM_FILTER_SIZE =
      CarbonV3DataFormatConstants.NUMBER_OF_ROWS_PER_BLOCKLET_COLUMN_PAGE_DEFAULT;
  /**
   * property for fpp(false-positive-probability) of bloom filter
   */
  private static final String BLOOM_FPP = "bloom_fpp";
  /**
   * default value for fpp of bloom filter is 1%
   */
  private static final double DEFAULT_BLOOM_FILTER_FPP = 0.01d;

  /**
   * property for compressing bloom while saving to disk.
   */
  private static final String COMPRESS_BLOOM = "bloom_compress";
  /**
   * Default value of compressing bloom while save to disk.
   */
  private static final boolean DEFAULT_BLOOM_COMPRESS = true;

  private DataMapMeta dataMapMeta;
  private String dataMapName;
  private int bloomFilterSize;
  private double bloomFilterFpp;
  private boolean bloomCompress;
  private Cache<BloomCacheKeyValue.CacheKey, BloomCacheKeyValue.CacheValue> cache;
  // segmentId -> list of index file
  private Map<String, Set<String>> segmentMap = new HashMap<>();

  public BloomCoarseGrainDataMapFactory(CarbonTable carbonTable, DataMapSchema dataMapSchema)
      throws MalformedDataMapCommandException {
    super(carbonTable, dataMapSchema);
    Objects.requireNonNull(carbonTable);
    Objects.requireNonNull(dataMapSchema);

    this.dataMapName = dataMapSchema.getDataMapName();

    List<CarbonColumn> indexedColumns = carbonTable.getIndexedColumns(dataMapSchema);
    this.bloomFilterSize = validateAndGetBloomFilterSize(dataMapSchema);
    this.bloomFilterFpp = validateAndGetBloomFilterFpp(dataMapSchema);
    this.bloomCompress = validateAndGetBloomCompress(dataMapSchema);
    List<ExpressionType> optimizedOperations = new ArrayList<ExpressionType>();
    // todo: support more optimize operations
    optimizedOperations.add(ExpressionType.EQUALS);
    this.dataMapMeta = new DataMapMeta(this.dataMapName, indexedColumns, optimizedOperations);
    LOGGER.info(String.format("DataMap %s works for %s with bloom size %d",
        this.dataMapName, this.dataMapMeta, this.bloomFilterSize));
    try {
      this.cache = CacheProvider.getInstance()
          .createCache(new CacheType("bloom_cache"), BloomDataMapCache.class.getName());
    } catch (Exception e) {
      LOGGER.error(e);
      throw new MalformedDataMapCommandException(e.getMessage());
    }
  }

  /**
   * validate Lucene DataMap BLOOM_SIZE
   * 1. BLOOM_SIZE property is optional, 32000 * 20 will be the default size.
   * 2. BLOOM_SIZE should be an integer that greater than 0
   */
  private int validateAndGetBloomFilterSize(DataMapSchema dmSchema)
      throws MalformedDataMapCommandException {
    String bloomFilterSizeStr = dmSchema.getProperties().get(BLOOM_SIZE);
    if (StringUtils.isBlank(bloomFilterSizeStr)) {
      LOGGER.warn(
          String.format("Bloom filter size is not configured for datamap %s, use default value %d",
              dataMapName, DEFAULT_BLOOM_FILTER_SIZE));
      return DEFAULT_BLOOM_FILTER_SIZE;
    }
    int bloomFilterSize;
    try {
      bloomFilterSize = Integer.parseInt(bloomFilterSizeStr);
    } catch (NumberFormatException e) {
      throw new MalformedDataMapCommandException(
          String.format("Invalid value of bloom filter size '%s', it should be an integer",
              bloomFilterSizeStr));
    }
    // todo: reconsider the boundaries of bloom filter size
    if (bloomFilterSize <= 0) {
      throw new MalformedDataMapCommandException(
          String.format("Invalid value of bloom filter size '%s', it should be greater than 0",
              bloomFilterSizeStr));
    }
    return bloomFilterSize;
  }

  /**
   * validate bloom DataMap BLOOM_FPP
   * 1. BLOOM_FPP property is optional, 0.00001 will be the default value.
   * 2. BLOOM_FPP should be (0, 1)
   */
  private double validateAndGetBloomFilterFpp(DataMapSchema dmSchema)
      throws MalformedDataMapCommandException {
    String bloomFilterFppStr = dmSchema.getProperties().get(BLOOM_FPP);
    if (StringUtils.isBlank(bloomFilterFppStr)) {
      LOGGER.warn(
          String.format("Bloom filter FPP is not configured for datamap %s, use default value %f",
              dataMapName, DEFAULT_BLOOM_FILTER_FPP));
      return DEFAULT_BLOOM_FILTER_FPP;
    }
    double bloomFilterFpp;
    try {
      bloomFilterFpp = Double.parseDouble(bloomFilterFppStr);
    } catch (NumberFormatException e) {
      throw new MalformedDataMapCommandException(
          String.format("Invalid value of bloom filter fpp '%s', it should be an numeric",
              bloomFilterFppStr));
    }
    if (bloomFilterFpp < 0 || bloomFilterFpp - 1 >= 0) {
      throw new MalformedDataMapCommandException(
          String.format("Invalid value of bloom filter fpp '%s', it should be in range 0~1",
              bloomFilterFppStr));
    }
    return bloomFilterFpp;
  }

  /**
   * validate bloom DataMap COMPRESS_BLOOM
   * Default value is true
   */
  private boolean validateAndGetBloomCompress(DataMapSchema dmSchema) {
    String bloomCompress = dmSchema.getProperties().get(COMPRESS_BLOOM);
    if (StringUtils.isBlank(bloomCompress)) {
      LOGGER.warn(
          String.format("Bloom compress is not configured for datamap %s, use default value %b",
              dataMapName, DEFAULT_BLOOM_COMPRESS));
      return DEFAULT_BLOOM_COMPRESS;
    }
    return Boolean.parseBoolean(bloomCompress);
  }

  @Override
  public DataMapWriter createWriter(Segment segment, String shardName,
      SegmentProperties segmentProperties) throws IOException {
    LOGGER.info(
        String.format("Data of BloomCoarseGranDataMap %s for table %s will be written to %s",
            this.dataMapName, getCarbonTable().getTableName() , shardName));
    return new BloomDataMapWriter(getCarbonTable().getTablePath(), this.dataMapName,
        this.dataMapMeta.getIndexedColumns(), segment, shardName, segmentProperties,
        this.bloomFilterSize, this.bloomFilterFpp, bloomCompress);
  }

  @Override
  public DataMapBuilder createBuilder(Segment segment, String shardName,
      SegmentProperties segmentProperties) throws IOException {
    return new BloomDataMapBuilder(getCarbonTable().getTablePath(), this.dataMapName,
        this.dataMapMeta.getIndexedColumns(), segment, shardName, segmentProperties,
        this.bloomFilterSize, this.bloomFilterFpp, bloomCompress);
  }

  @Override
  public List<CoarseGrainDataMap> getDataMaps(Segment segment) throws IOException {
    List<CoarseGrainDataMap> dataMaps = new ArrayList<CoarseGrainDataMap>(1);
    try {
      Set<String> shardPaths = segmentMap.get(segment.getSegmentNo());
      if (shardPaths == null) {
        String dataMapStorePath = DataMapWriter.getDefaultDataMapPath(
            getCarbonTable().getTablePath(), segment.getSegmentNo(), dataMapName);
        CarbonFile[] carbonFiles = FileFactory.getCarbonFile(dataMapStorePath).listFiles();
        shardPaths = new HashSet<>();
        for (CarbonFile carbonFile : carbonFiles) {
          shardPaths.add(carbonFile.getAbsolutePath());
        }
        segmentMap.put(segment.getSegmentNo(), shardPaths);
      }
      for (String shard : shardPaths) {
        BloomCoarseGrainDataMap bloomDM = new BloomCoarseGrainDataMap();
        bloomDM.init(new BloomDataMapModel(shard, cache));
        bloomDM.initIndexColumnConverters(getCarbonTable(), dataMapMeta.getIndexedColumns());
        dataMaps.add(bloomDM);
      }
    } catch (Exception e) {
      throw new IOException("Error occurs while init Bloom DataMap", e);
    }
    return dataMaps;
  }

  @Override
  public List<CoarseGrainDataMap> getDataMaps(DataMapDistributable distributable)
      throws IOException {
    List<CoarseGrainDataMap> coarseGrainDataMaps = new ArrayList<>();
    BloomCoarseGrainDataMap bloomCoarseGrainDataMap = new BloomCoarseGrainDataMap();
    String indexPath = ((BloomDataMapDistributable) distributable).getIndexPath();
    bloomCoarseGrainDataMap.init(new BloomDataMapModel(indexPath, cache));
    bloomCoarseGrainDataMap.initIndexColumnConverters(getCarbonTable(),
        dataMapMeta.getIndexedColumns());
    coarseGrainDataMaps.add(bloomCoarseGrainDataMap);
    return coarseGrainDataMaps;
  }

  /**
   * returns all the directories of lucene index files for query
   * Note: copied from luceneDataMapFactory, will extract to a common interface
   */
  private CarbonFile[] getAllIndexDirs(String tablePath, String segmentId) {
    List<CarbonFile> indexDirs = new ArrayList<>();
    List<TableDataMap> dataMaps;
    try {
      // there can be multiple bloom datamaps present on a table, so get all datamaps and form
      // the path till the index file directories in all datamaps folders present in each segment
      dataMaps = DataMapStoreManager.getInstance().getAllDataMap(getCarbonTable());
    } catch (IOException ex) {
      LOGGER.error(ex, String.format("failed to get datamaps for tablePath %s, segmentId %s",
          tablePath, segmentId));
      throw new RuntimeException(ex);
    }
    if (dataMaps.size() > 0) {
      for (TableDataMap dataMap : dataMaps) {
        List<CarbonFile> indexFiles;
        String dmPath = CarbonTablePath
            .getDataMapStorePath(tablePath, segmentId, dataMap.getDataMapSchema().getDataMapName());
        FileFactory.FileType fileType = FileFactory.getFileType(dmPath);
        final CarbonFile dirPath = FileFactory.getCarbonFile(dmPath, fileType);
        indexFiles = Arrays.asList(dirPath.listFiles(new CarbonFileFilter() {
          @Override
          public boolean accept(CarbonFile file) {
            return file.isDirectory();
          }
        }));
        indexDirs.addAll(indexFiles);
      }
    }
    return indexDirs.toArray(new CarbonFile[0]);
  }

  @Override
  public List<DataMapDistributable> toDistributable(Segment segment) {
    List<DataMapDistributable> dataMapDistributableList = new ArrayList<>();
    CarbonFile[] indexDirs =
        getAllIndexDirs(getCarbonTable().getTablePath(), segment.getSegmentNo());
    if (segment.getFilteredIndexShardNames().size() == 0) {
      for (CarbonFile indexDir : indexDirs) {
        DataMapDistributable bloomDataMapDistributable = new BloomDataMapDistributable(
            indexDir.getAbsolutePath());
        dataMapDistributableList.add(bloomDataMapDistributable);
      }
      return dataMapDistributableList;
    }
    for (CarbonFile indexDir : indexDirs) {
      // Filter out the tasks which are filtered through CG datamap.
      if (!segment.getFilteredIndexShardNames().contains(indexDir.getName())) {
        continue;
      }
      DataMapDistributable bloomDataMapDistributable = new BloomDataMapDistributable(
          indexDir.getAbsolutePath());
      dataMapDistributableList.add(bloomDataMapDistributable);
    }
    return dataMapDistributableList;
  }

  @Override
  public void fireEvent(Event event) {

  }

  @Override
  public void clear(Segment segment) {
    Set<String> shards = segmentMap.remove(segment.getSegmentNo());
    if (shards != null) {
      for (String shard : shards) {
        for (CarbonColumn carbonColumn : dataMapMeta.getIndexedColumns()) {
          cache.invalidate(new BloomCacheKeyValue.CacheKey(shard, carbonColumn.getColName()));
        }
      }
    }
  }

  @Override
  public void clear() {
    if (segmentMap.size() > 0) {
      for (String segmentId : segmentMap.keySet().toArray(new String[segmentMap.size()])) {
        clear(new Segment(segmentId, null, null));
      }
    }
  }

  @Override
  public void deleteDatamapData() {
    SegmentStatusManager ssm =
        new SegmentStatusManager(getCarbonTable().getAbsoluteTableIdentifier());
    try {
      List<Segment> validSegments = ssm.getValidAndInvalidSegments().getValidSegments();
      for (Segment segment : validSegments) {
        String segmentId = segment.getSegmentNo();
        String datamapPath = CarbonTablePath
            .getDataMapStorePath(getCarbonTable().getTablePath(), segmentId, dataMapName);
        if (FileFactory.isFileExist(datamapPath)) {
          CarbonFile file = FileFactory.getCarbonFile(datamapPath,
              FileFactory.getFileType(datamapPath));
          CarbonUtil.deleteFoldersAndFilesSilent(file);
        }
      }
    } catch (IOException | InterruptedException ex) {
      LOGGER.error("drop datamap failed, failed to delete datamap directory");
    }
  }

  @Override public boolean willBecomeStale(TableOperation operation) {
    return false;
  }

  @Override
  public DataMapMeta getMeta() {
    return this.dataMapMeta;
  }

  @Override
  public DataMapLevel getDataMapLevel() {
    return DataMapLevel.CG;
  }
}
