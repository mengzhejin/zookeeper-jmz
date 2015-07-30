// File generated by hadoop record compiler. Do not edit.
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

package org.apache.zookeeper.proto;

import java.util.*;
import org.apache.jute.*;

/**
 * 获取子节点的响应：
 * @children 子节点结果
 * @stat     当前节点的stat
 * 
 * */
public class GetChildren2Response implements Record {
  private java.util.List<String> children;
  private org.apache.zookeeper.data.Stat stat;
  public GetChildren2Response() {
  }
  public GetChildren2Response(
        java.util.List<String> children,
        org.apache.zookeeper.data.Stat stat) {
    this.children=children;
    this.stat=stat;
  }
  public java.util.List<String> getChildren() {
    return children;
  }
  public void setChildren(java.util.List<String> m_) {
    children=m_;
  }
  public org.apache.zookeeper.data.Stat getStat() {
    return stat;
  }
  public void setStat(org.apache.zookeeper.data.Stat m_) {
    stat=m_;
  }
  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(this,tag);
    {
      a_.startVector(children,"children");
      if (children!= null) {          int len1 = children.size();
          for(int vidx1 = 0; vidx1<len1; vidx1++) {
            String e1 = (String) children.get(vidx1);
        a_.writeString(e1,"e1");
          }
      }
      a_.endVector(children,"children");
    }
    a_.writeRecord(stat,"stat");
    a_.endRecord(this,tag);
  }
  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(tag);
    {
      Index vidx1 = a_.startVector("children");
      if (vidx1!= null) {          children=new java.util.ArrayList<String>();
          for (; !vidx1.done(); vidx1.incr()) {
    String e1;
        e1=a_.readString("e1");
            children.add(e1);
          }
      }
    a_.endVector("children");
    }
    stat= new org.apache.zookeeper.data.Stat();
    a_.readRecord(stat,"stat");
    a_.endRecord(tag);
}
  public String toString() {
    try {
      java.io.ByteArrayOutputStream s =
        new java.io.ByteArrayOutputStream();
      CsvOutputArchive a_ = 
        new CsvOutputArchive(s);
      a_.startRecord(this,"");
    {
      a_.startVector(children,"children");
      if (children!= null) {          int len1 = children.size();
          for(int vidx1 = 0; vidx1<len1; vidx1++) {
            String e1 = (String) children.get(vidx1);
        a_.writeString(e1,"e1");
          }
      }
      a_.endVector(children,"children");
    }
    a_.writeRecord(stat,"stat");
      a_.endRecord(this,"");
      return new String(s.toByteArray(), "UTF-8");
    } catch (Throwable ex) {
      ex.printStackTrace();
    }
    return "ERROR";
  }
  public void write(java.io.DataOutput out) throws java.io.IOException {
    BinaryOutputArchive archive = new BinaryOutputArchive(out);
    serialize(archive, "");
  }
  public void readFields(java.io.DataInput in) throws java.io.IOException {
    BinaryInputArchive archive = new BinaryInputArchive(in);
    deserialize(archive, "");
  }
  public int compareTo (Object peer_) throws ClassCastException {
    throw new UnsupportedOperationException("comparing GetChildren2Response is unimplemented");
  }
  public boolean equals(Object peer_) {
    if (!(peer_ instanceof GetChildren2Response)) {
      return false;
    }
    if (peer_ == this) {
      return true;
    }
    GetChildren2Response peer = (GetChildren2Response) peer_;
    boolean ret = false;
    ret = children.equals(peer.children);
    if (!ret) return ret;
    ret = stat.equals(peer.stat);
    if (!ret) return ret;
     return ret;
  }
  public int hashCode() {
    int result = 17;
    int ret;
    ret = children.hashCode();
    result = 37*result + ret;
    ret = stat.hashCode();
    result = 37*result + ret;
    return result;
  }
  public static String signature() {
    return "LGetChildren2Response([s]LStat(lllliiiliil))";
  }
}
