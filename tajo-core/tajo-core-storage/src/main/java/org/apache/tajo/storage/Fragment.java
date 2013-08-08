/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.storage;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import org.apache.hadoop.fs.Path;
import org.apache.tajo.json.GsonObject;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos.FragmentProto;
import org.apache.tajo.catalog.proto.CatalogProtos.SchemaProto;
import org.apache.tajo.storage.json.StorageGsonHelper;
import org.apache.tajo.util.TUtil;

public class Fragment implements TableDesc, Comparable<Fragment>, SchemaObject, GsonObject {
  protected FragmentProto.Builder builder = null;

  @Expose private String fragmentId; // required
  @Expose private Path path; // required
  @Expose private TableMeta meta; // required
  @Expose private Long startOffset; // required
  @Expose private Long length; // required
  @Expose private boolean distCached = false; // optional

  private String [] dataLocations;

  public Fragment() {
    builder = FragmentProto.newBuilder();
  }

  public Fragment(String fragmentId, Path path, TableMeta meta, long start,
      long length, String [] dataLocations) {
    this();
    TableMeta newMeta = new TableMetaImpl(meta.getProto());
    SchemaProto newSchemaProto = CatalogUtil.getQualfiedSchema(fragmentId, meta
        .getSchema().getProto());
    newMeta.setSchema(new Schema(newSchemaProto));
    this.set(fragmentId, path, newMeta, start, length);
    this.dataLocations = dataLocations;
  }

  public Fragment(FragmentProto proto) {
    this();
    TableMeta newMeta = new TableMetaImpl(proto.getMeta());
    this.set(proto.getId(), new Path(proto.getPath()), newMeta,
        proto.getStartOffset(), proto.getLength());
    if (proto.hasDistCached() && proto.getDistCached()) {
      distCached = true;
    }
  }

  public boolean hasDataLocations() {
    return this.dataLocations != null;
  }

  public String [] getDataLocations() {
    return this.dataLocations;
  }

  private void set(String fragmentId, Path path, TableMeta meta, long start,
      long length) {
    this.fragmentId = fragmentId;
    this.path = path;
    this.meta = meta;
    this.startOffset = start;
    this.length = length;
  }

  public String getId() {
    return this.fragmentId;
  }

  @Override
  public void setId(String fragmentId) {
    this.fragmentId = fragmentId;
  }
  
  @Override
  public Path getPath() {
    return this.path;
  }

  @Override
  public void setPath(Path path) {
    this.path = path;
  }
  
  public Schema getSchema() {
    return getMeta().getSchema();
  }

  public TableMeta getMeta() {
    return this.meta;
  }

  @Override
  public void setMeta(TableMeta meta) {
    this.meta = meta;
  }

  public Long getStartOffset() {
    return this.startOffset;
  }

  public Long getLength() {
    return this.length;
  }

  public Boolean isDistCached() {
    return this.distCached;
  }

  public void setDistCached() {
    this.distCached = true;
  }

  /**
   * 
   * The offset range of tablets <b>MUST NOT</b> be overlapped.
   * 
   * @param t
   * @return If the table paths are not same, return -1.
   */
  @Override
  public int compareTo(Fragment t) {
    if (getPath().equals(t.getPath())) {
      long diff = this.getStartOffset() - t.getStartOffset();
      if (diff < 0) {
        return -1;
      } else if (diff > 0) {
        return 1;
      } else {
        return 0;
      }
    } else {
      return -1;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof Fragment) {
      Fragment t = (Fragment) o;
      if (getPath().equals(t.getPath())
          && TUtil.checkEquals(t.getStartOffset(), this.getStartOffset())
          && TUtil.checkEquals(t.getLength(), this.getLength())
          && TUtil.checkEquals(t.isDistCached(), this.isDistCached())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(fragmentId, path, startOffset, length, isDistCached());
  }
  
  public Object clone() throws CloneNotSupportedException {
    Fragment frag = (Fragment) super.clone();
    frag.builder = FragmentProto.newBuilder();
    frag.fragmentId = fragmentId;
    frag.path = path;
    frag.meta = (TableMeta) (meta != null ? meta.clone() : null);
    frag.distCached = distCached;
    
    return frag;
  }

  @Override
  public String toString() {
    return "\"fragment\": {\"id\": \""+fragmentId+"\", \"path\": "
    		+getPath() + "\", \"start\": " + this.getStartOffset() + ",\"length\": "
        + getLength() + ", \"distCached\": " + distCached + "}" ;
  }

  @Override
  public FragmentProto getProto() {
    if (builder == null) {
      builder = FragmentProto.newBuilder();
    }
    builder.setId(this.fragmentId);
    builder.setStartOffset(this.startOffset);
    builder.setMeta(meta.getProto());
    builder.setLength(this.length);
    builder.setPath(this.path.toString());
    builder.setDistCached(this.distCached);

    return builder.build();
  }

  @Override
  public String toJson() {
	  Gson gson = StorageGsonHelper.getInstance();
	  return gson.toJson(this, TableDesc.class);
  }
}
