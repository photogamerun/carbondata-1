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

package org.apache.carbondata.core.datastore.page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.TableSpec;
import org.apache.carbondata.core.memory.CarbonUnsafe;
import org.apache.carbondata.core.memory.MemoryBlock;
import org.apache.carbondata.core.memory.MemoryException;
import org.apache.carbondata.core.memory.UnsafeMemoryManager;
import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.datatype.DecimalConverterFactory;
import org.apache.carbondata.core.util.ByteUtil;
import org.apache.carbondata.core.util.ThreadLocalTaskInfo;

public abstract class VarLengthColumnPageBase extends ColumnPage {

  static final int byteBits = DataTypes.BYTE.getSizeBits();
  static final int shortBits = DataTypes.SHORT.getSizeBits();
  static final int intBits = DataTypes.INT.getSizeBits();
  static final int longBits = DataTypes.LONG.getSizeBits();
  // default size for each row, grows as needed
  static final int DEFAULT_ROW_SIZE = 8;

  static final double FACTOR = 1.25;

  final long taskId = ThreadLocalTaskInfo.getCarbonTaskInfo().getTaskId();

  // memory allocated by Unsafe
  MemoryBlock memoryBlock;

  // base address of memoryBlock
  Object baseAddress;

  // the offset of row in the unsafe memory, its size is pageSize + 1
  List<Integer> rowOffset;

  // the length of bytes added in the page
  int totalLength;

  // base offset of memoryBlock
  long baseOffset;

  // size of the allocated memory, in bytes
  int capacity;

  VarLengthColumnPageBase(TableSpec.ColumnSpec columnSpec, DataType dataType, int pageSize) {
    super(columnSpec, dataType, pageSize);
    rowOffset = new ArrayList<>();
    totalLength = 0;
  }

  @Override
  public void setBytePage(byte[] byteData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setShortPage(short[] shortData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setShortIntPage(byte[] shortIntData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setIntPage(int[] intData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setLongPage(long[] longData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setFloatPage(float[] floatData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void setDoublePage(double[] doubleData) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  /**
   * Create a new column page for decimal page
   */
  static ColumnPage newDecimalColumnPage(TableSpec.ColumnSpec columnSpec, byte[] lvEncodedBytes)
      throws MemoryException {
    DecimalConverterFactory.DecimalConverter decimalConverter =
        DecimalConverterFactory.INSTANCE.getDecimalConverter(columnSpec.getPrecision(),
            columnSpec.getScale());
    int size = decimalConverter.getSize();
    if (size < 0) {
      return getLVBytesColumnPage(columnSpec, lvEncodedBytes,
          DataTypes.createDecimalType(columnSpec.getPrecision(), columnSpec.getScale()),
          CarbonCommonConstants.INT_SIZE_IN_BYTE);
    } else {
      // Here the size is always fixed.
      return getDecimalColumnPage(columnSpec, lvEncodedBytes, size);
    }
  }

  /**
   * Create a new column page based on the LV (Length Value) encoded bytes
   */
  static ColumnPage newLVBytesColumnPage(TableSpec.ColumnSpec columnSpec, byte[] lvEncodedBytes,
      int lvLength) throws MemoryException {
    return getLVBytesColumnPage(columnSpec, lvEncodedBytes, DataTypes.BYTE_ARRAY, lvLength);
  }

  /**
   * Create a new column page based on the LV (Length Value) encoded bytes
   */
  static ColumnPage newComplexLVBytesColumnPage(TableSpec.ColumnSpec columnSpec,
      byte[] lvEncodedBytes, int lvLength) throws MemoryException {
    return getComplexLVBytesColumnPage(columnSpec, lvEncodedBytes, DataTypes.BYTE_ARRAY, lvLength);
  }

  private static ColumnPage getDecimalColumnPage(TableSpec.ColumnSpec columnSpec,
      byte[] lvEncodedBytes, int size) throws MemoryException {
    List<Integer> rowOffset = new ArrayList<>();
    int offset;
    int rowId = 0;
    for (offset = 0; offset < lvEncodedBytes.length; offset += size) {
      rowOffset.add(offset);
      rowId++;
    }
    rowOffset.add(offset);

    VarLengthColumnPageBase page;
    if (unsafe) {
      page = new UnsafeDecimalColumnPage(columnSpec, columnSpec.getSchemaDataType(), rowId);
    } else {
      page = new SafeDecimalColumnPage(columnSpec, columnSpec.getSchemaDataType(), rowId);
    }

    // set total length and rowOffset in page
    page.totalLength = offset;
    page.rowOffset = new ArrayList<>();
    for (int i = 0; i < rowOffset.size(); i++) {
      page.rowOffset.add(rowOffset.get(i));
    }
    for (int i = 0; i < rowId; i++) {
      page.putBytes(i, lvEncodedBytes, i * size, size);
    }
    return page;
  }

  private static ColumnPage getLVBytesColumnPage(TableSpec.ColumnSpec columnSpec,
      byte[] lvEncodedBytes, DataType dataType, int lvLength)
      throws MemoryException {
    // extract length and data, set them to rowOffset and unsafe memory correspondingly
    int rowId = 0;
    List<Integer> rowOffset = new ArrayList<>();
    List<Integer> rowLength = new ArrayList<>();
    int length;
    int offset;
    int lvEncodedOffset = 0;

    // extract Length field in input and calculate total length
    for (offset = 0; lvEncodedOffset < lvEncodedBytes.length; offset += length) {
      length = ByteUtil.toInt(lvEncodedBytes, lvEncodedOffset);
      rowOffset.add(offset);
      rowLength.add(length);
      lvEncodedOffset += lvLength + length;
      rowId++;
    }
    rowOffset.add(offset);
    VarLengthColumnPageBase page =
        getVarLengthColumnPage(columnSpec, lvEncodedBytes, dataType, lvLength, rowId, rowOffset,
            rowLength, offset);
    return page;
  }

  private static ColumnPage getComplexLVBytesColumnPage(TableSpec.ColumnSpec columnSpec,
      byte[] lvEncodedBytes, DataType dataType, int lvLength)
      throws MemoryException {
    // extract length and data, set them to rowOffset and unsafe memory correspondingly
    int rowId = 0;
    List<Integer> rowOffset = new ArrayList<>();
    List<Integer> rowLength = new ArrayList<>();
    int length;
    int offset;
    int lvEncodedOffset = 0;

    // extract Length field in input and calculate total length
    for (offset = 0; lvEncodedOffset < lvEncodedBytes.length; offset += length) {
      length = ByteUtil.toShort(lvEncodedBytes, lvEncodedOffset);
      rowOffset.add(offset);
      rowLength.add(length);
      lvEncodedOffset += lvLength + length;
      rowId++;
    }
    rowOffset.add(offset);

    VarLengthColumnPageBase page =
        getVarLengthColumnPage(columnSpec, lvEncodedBytes, dataType, lvLength, rowId, rowOffset,
            rowLength, offset);
    return page;
  }

  private static VarLengthColumnPageBase getVarLengthColumnPage(TableSpec.ColumnSpec columnSpec,
      byte[] lvEncodedBytes, DataType dataType, int lvLength, int rowId, List<Integer> rowOffset,
      List<Integer> rowLength, int offset) throws MemoryException {
    int lvEncodedOffset;
    int length;
    int numRows = rowId;

    VarLengthColumnPageBase page;
    int inputDataLength = offset;
    if (unsafe) {
      page = new UnsafeDecimalColumnPage(columnSpec, dataType, numRows, inputDataLength);
    } else {
      page = new SafeDecimalColumnPage(columnSpec, dataType, numRows);
    }

    // set total length and rowOffset in page
    page.totalLength = offset;
    page.rowOffset = new ArrayList<>();
    for (int i = 0; i < rowOffset.size(); i++) {
      page.rowOffset.add(rowOffset.get(i));
    }

    // set data in page
    lvEncodedOffset = 0;
    for (int i = 0; i < numRows; i++) {
      length = rowLength.get(i);
      page.putBytes(i, lvEncodedBytes, lvEncodedOffset + lvLength, length);
      lvEncodedOffset += lvLength + length;
    }
    return page;
  }

  @Override
  public void putByte(int rowId, byte value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void putShort(int rowId, short value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void putShortInt(int rowId, int value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void putInt(int rowId, int value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void putLong(int rowId, long value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public void putDouble(int rowId, double value) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  abstract void putBytesAtRow(int rowId, byte[] bytes);

  @Override
  public void putBytes(int rowId, byte[] bytes) {
    // rowId * 4 represents the length of L in LV
    if (bytes.length > (Integer.MAX_VALUE - totalLength - rowId * 4)) {
      // since we later store a column page in a byte array, so its maximum size is 2GB
      throw new RuntimeException("Carbondata only support maximum 2GB size for one column page,"
          + " exceed this limit at rowId " + rowId);
    }
    if (rowId == 0) {
      rowOffset.add(0);
    }
    rowOffset.add(rowOffset.get(rowId) + bytes.length);
    putBytesAtRow(rowId, bytes);
    totalLength += bytes.length;
  }

  @Override
  public byte getByte(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public short getShort(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public int getShortInt(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public int getInt(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public long getLong(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public float getFloat(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public double getDouble(int rowId) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public byte[] getBytePage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public short[] getShortPage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public byte[] getShortIntPage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public int[] getIntPage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public long[] getLongPage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public float[] getFloatPage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public double[] getDoublePage() {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  @Override
  public byte[] getDecimalPage() {
    // output LV encoded byte array
    int offset = 0;
    byte[] data = new byte[totalLength];
    for (int rowId = 0; rowId < pageSize; rowId++) {
      int length = rowOffset.get(rowId + 1) - rowOffset.get(rowId);
      copyBytes(rowId, data, offset, length);
      offset += length;
    }
    return data;
  }

  /**
   * Copy `length` bytes from data at rowId to dest start from destOffset
   */
  abstract void copyBytes(int rowId, byte[] dest, int destOffset, int length);

  @Override
  public byte[] getLVFlattenedBytePage() throws IOException {
    // output LV encoded byte array
    int offset = 0;
    byte[] data = new byte[totalLength + ((rowOffset.size() - 1) * 4)];
    for (int rowId = 0; rowId < rowOffset.size() - 1; rowId++) {
      int length = rowOffset.get(rowId + 1) - rowOffset.get(rowId);
      ByteUtil.setInt(data, offset, length);
      copyBytes(rowId, data, offset + 4, length);
      offset += 4 + length;
    }
    return data;
  }

  @Override public byte[] getComplexChildrenLVFlattenedBytePage() throws IOException {
    // output LV encoded byte array
    int offset = 0;
    byte[] data = new byte[totalLength + ((rowOffset.size() - 1) * 2)];
    for (int rowId = 0; rowId < rowOffset.size() - 1; rowId++) {
      short length = (short) (rowOffset.get(rowId + 1) - rowOffset.get(rowId));
      ByteUtil.setShort(data, offset, length);
      copyBytes(rowId, data, offset + 2, length);
      offset += 2 + length;
    }
    return data;
  }

  @Override
  public byte[] getComplexParentFlattenedBytePage() throws IOException {
    // output LV encoded byte array
    int offset = 0;
    byte[] data = new byte[totalLength];
    for (int rowId = 0; rowId < rowOffset.size() - 1; rowId++) {
      short length = (short) (rowOffset.get(rowId + 1) - rowOffset.get(rowId));
      copyBytes(rowId, data, offset, length);
      offset += length;
    }
    return data;
  }

  @Override
  public void convertValue(ColumnPageValueConverter codec) {
    throw new UnsupportedOperationException("invalid data type: " + dataType);
  }

  /**
   * reallocate memory if capacity length than current size + request size
   */
  protected void ensureMemory(int requestSize) throws MemoryException {
    if (totalLength + requestSize > capacity) {
      int newSize = Math.max(2 * capacity, totalLength + requestSize);
      MemoryBlock newBlock = UnsafeMemoryManager.allocateMemoryWithRetry(taskId, newSize);
      CarbonUnsafe.getUnsafe().copyMemory(baseAddress, baseOffset,
          newBlock.getBaseObject(), newBlock.getBaseOffset(), capacity);
      UnsafeMemoryManager.INSTANCE.freeMemory(taskId, memoryBlock);
      memoryBlock = newBlock;
      baseAddress = newBlock.getBaseObject();
      baseOffset = newBlock.getBaseOffset();
      capacity = newSize;
    }
  }
}
