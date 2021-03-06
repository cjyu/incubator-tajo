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

package org.apache.tajo.algebra;

import com.google.common.base.Objects;
import org.apache.tajo.util.TUtil;

public class FunctionExpr extends Expr {
  private String signature;
  private Expr[] params;

  public FunctionExpr(String signature) {
    this(OpType.Function, signature);
  }

  public FunctionExpr(String signature, Expr [] params) {
    this(OpType.Function, signature);
    setParams(params);
  }

  protected FunctionExpr(OpType type, String signature) {
    super(type);
    if (type != OpType.Function && type != OpType.CountRowsFunction) {
      throw new IllegalArgumentException("FunctionExpr cannot accept " + type + "type");
    }
    this.signature = signature.toLowerCase();
  }

  protected FunctionExpr(OpType type, String signature, Expr [] params) {
    super(type);
    if (type != OpType.Function && type != OpType.GeneralSetFunction) {
      throw new IllegalArgumentException("FunctionExpr cannot accept " + type + "type");
    }
    this.signature = signature.toLowerCase();
    setParams(params);
  }

  public String getSignature() {
    return this.signature;
  }

  public Expr[] getParams() {
    return params;
  }

  public void setParams(Expr[] params) {
    this.params = params;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(signature, Objects.hashCode(params));
  }

  @Override
  boolean equalsTo(Expr expr) {
    FunctionExpr another = (FunctionExpr) expr;
    return signature.equals(another.signature) &&
        TUtil.checkEquals(params, another.params);
  }

  public String toJson() {
    return JsonHelper.toJson(this);
  }
}
