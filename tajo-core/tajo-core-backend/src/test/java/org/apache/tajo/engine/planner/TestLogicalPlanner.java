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

package org.apache.tajo.engine.planner;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.hadoop.fs.Path;
import org.apache.tajo.TajoTestingCluster;
import org.apache.tajo.algebra.Expr;
import org.apache.tajo.algebra.JoinType;
import org.apache.tajo.benchmark.TPCH;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos.FunctionType;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.common.TajoDataTypes.Type;
import org.apache.tajo.engine.eval.EvalNode;
import org.apache.tajo.engine.function.builtin.SumInt;
import org.apache.tajo.engine.json.CoreGsonHelper;
import org.apache.tajo.engine.parser.SQLAnalyzer;
import org.apache.tajo.engine.planner.logical.*;
import org.apache.tajo.master.TajoMaster;
import org.apache.tajo.util.FileUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestLogicalPlanner {
  private static TajoTestingCluster util;
  private static CatalogService catalog;
  private static SQLAnalyzer sqlAnalyzer;
  private static LogicalPlanner planner;
  private static TPCH tpch;

  @BeforeClass
  public static void setUp() throws Exception {
    util = new TajoTestingCluster();
    util.startCatalogCluster();
    catalog = util.getMiniCatalogCluster().getCatalog();
    for (FunctionDesc funcDesc : TajoMaster.initBuiltinFunctions()) {
      catalog.registerFunction(funcDesc);
    }
    
    Schema schema = new Schema();
    schema.addColumn("name", Type.TEXT);
    schema.addColumn("empid", Type.INT4);
    schema.addColumn("deptname", Type.TEXT);

    Schema schema2 = new Schema();
    schema2.addColumn("deptname", Type.TEXT);
    schema2.addColumn("manager", Type.TEXT);

    Schema schema3 = new Schema();
    schema3.addColumn("deptname", Type.TEXT);
    schema3.addColumn("score", Type.INT4);

    TableMeta meta = CatalogUtil.newTableMeta(schema, StoreType.CSV);
    TableDesc people = new TableDescImpl("employee", meta,
        new Path("file:///"));
    catalog.addTable(people);

    TableDesc student = new TableDescImpl("dept", schema2, StoreType.CSV,
        new Options(),
        new Path("file:///"));
    catalog.addTable(student);

    TableDesc score = new TableDescImpl("score", schema3, StoreType.CSV,
        new Options(),
        new Path("file:///"));
    catalog.addTable(score);

    FunctionDesc funcDesc = new FunctionDesc("sumtest", SumInt.class, FunctionType.AGGREGATION,
        CatalogUtil.newDataTypesWithoutLen(Type.INT4),
        CatalogUtil.newDataTypesWithoutLen(Type.INT4));


    // TPC-H Schema for Complex Queries
    String [] tpchTables = {
        "part", "supplier", "partsupp", "nation", "region"
    };
    tpch = new TPCH();
    tpch.loadSchemas();
    tpch.loadOutSchema();
    for (String table : tpchTables) {
      TableMeta m = CatalogUtil.newTableMeta(tpch.getSchema(table), StoreType.CSV);
      TableDesc d = CatalogUtil.newTableDesc(table, m, new Path("file:///"));
      catalog.addTable(d);
    }

    catalog.registerFunction(funcDesc);
    sqlAnalyzer = new SQLAnalyzer();
    planner = new LogicalPlanner(catalog);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    util.shutdownCatalogCluster();
  }

  static String[] QUERIES = {
      "select name, empid, deptname from employee where empId > 500", // 0
      "select name, empid, e.deptname, manager from employee as e, dept as dp", // 1
      "select name, empid, e.deptname, manager, score from employee as e, dept, score", // 2
      "select p.deptname, sumtest(score) from dept as p, score group by p.deptName having sumtest(score) > 30", // 3
      "select p.deptname, score from dept as p, score order by score asc", // 4
      "select name from employee where empId = 100", // 5
      "select name, score from employee, score", // 6
      "select p.deptName, sumtest(score) from dept as p, score group by p.deptName", // 7
      "create table store1 as select p.deptName, sumtest(score) from dept as p, score group by p.deptName", // 8
      "select deptName, sumtest(score) from score group by deptName having sumtest(score) > 30", // 9
      "select 7 + 8 as res1, 8 * 9 as res2, 10 * 10 as res3", // 10
      "create index idx_employee on employee using bitmap (name null first, empId desc) with ('fillfactor' = 70)", // 11
      "select name, score from employee, score order by score limit 3" // 12
  };

  @Test
  public final void testSingleRelation() throws CloneNotSupportedException {
    Expr expr = sqlAnalyzer.parse(QUERIES[0]);
    LogicalPlan planNode = planner.createPlan(expr);
    LogicalNode plan = planNode.getRootBlock().getRoot();
    assertEquals(ExprType.ROOT, plan.getType());
    TestLogicalNode.testCloneLogicalNode(plan);
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();

    assertEquals(ExprType.SELECTION, projNode.getSubNode().getType());
    SelectionNode selNode = (SelectionNode) projNode.getSubNode();

    assertEquals(ExprType.SCAN, selNode.getSubNode().getType());
    ScanNode scanNode = (ScanNode) selNode.getSubNode();
    assertEquals("employee", scanNode.getTableId());
  }

  public static void assertSchema(Schema expected, Schema schema) {
    Column expectedColumn;
    Column column;
    for (int i = 0; i < expected.getColumnNum(); i++) {
      expectedColumn = expected.getColumn(i);
      column = schema.getColumnByName(expectedColumn.getColumnName());
      assertEquals(expectedColumn.getColumnName(), column.getColumnName());
      assertEquals(expectedColumn.getDataType(), column.getDataType());
    }
  }

  @Test
  public final void testImplicityJoinPlan() throws CloneNotSupportedException {
    // two relations
    Expr expr = sqlAnalyzer.parse(QUERIES[1]);
    LogicalPlan planNode = planner.createPlan(expr);
    LogicalNode plan = planNode.getRootBlock().getRoot();

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    TestLogicalNode.testCloneLogicalNode(root);

    Schema expectedSchema = new Schema();
    expectedSchema.addColumn("name", Type.TEXT);
    expectedSchema.addColumn("empid", Type.INT4);
    expectedSchema.addColumn("deptname", Type.TEXT);
    expectedSchema.addColumn("manager", Type.TEXT);
    for (int i = 0; i < expectedSchema.getColumnNum(); i++) {
      Column found = root.getOutSchema().getColumnByName(expectedSchema.getColumn(i).
          getColumnName());
      assertEquals(expectedSchema.getColumn(i).getDataType(), found.getDataType());
    }

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();

    assertEquals(ExprType.JOIN, projNode.getSubNode().getType());
    JoinNode joinNode = (JoinNode) projNode.getSubNode();

    assertEquals(ExprType.SCAN, joinNode.getOuterNode().getType());
    ScanNode leftNode = (ScanNode) joinNode.getOuterNode();
    assertEquals("employee", leftNode.getTableId());
    assertEquals(ExprType.SCAN, joinNode.getInnerNode().getType());
    ScanNode rightNode = (ScanNode) joinNode.getInnerNode();
    assertEquals("dept", rightNode.getTableId());
    /*
    LogicalNode optimized = LogicalOptimizer.optimize(expr, plan);
    assertSchema(expectedSchema, optimized.getOutSchema());
    */


    // three relations
    expr = sqlAnalyzer.parse(QUERIES[2]);
    plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    expectedSchema.addColumn("score", Type.INT4);
    assertSchema(expectedSchema, plan.getOutSchema());

    assertEquals(ExprType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    projNode = (ProjectionNode) root.getSubNode();

    assertEquals(ExprType.JOIN, projNode.getSubNode().getType());
    joinNode = (JoinNode) projNode.getSubNode();

    assertEquals(ExprType.JOIN, joinNode.getOuterNode().getType());

    assertEquals(ExprType.SCAN, joinNode.getInnerNode().getType());
    ScanNode scan1 = (ScanNode) joinNode.getInnerNode();
    assertEquals("score", scan1.getTableId());

    JoinNode leftNode2 = (JoinNode) joinNode.getOuterNode();
    assertEquals(ExprType.JOIN, leftNode2.getType());

    assertEquals(ExprType.SCAN, leftNode2.getOuterNode().getType());
    ScanNode leftScan = (ScanNode) leftNode2.getOuterNode();
    assertEquals("employee", leftScan.getTableId());

    assertEquals(ExprType.SCAN, leftNode2.getInnerNode().getType());
    ScanNode rightScan = (ScanNode) leftNode2.getInnerNode();
    assertEquals("dept", rightScan.getTableId());
    /*
    optimized = LogicalOptimizer.optimize(expr, plan);
    assertSchema(expectedSchema, optimized.getOutSchema());*/
  }



  String [] JOINS = {
      "select name, dept.deptName, score from employee natural join dept natural join score", // 0
      "select name, dept.deptName, score from employee inner join dept on employee.deptName = dept.deptName inner join score on dept.deptName = score.deptName", // 1
      "select name, dept.deptName, score from employee left outer join dept on employee.deptName = dept.deptName right outer join score on dept.deptName = score.deptName" // 2
  };

  static Schema expectedJoinSchema;
  static {
    expectedJoinSchema = new Schema();
    expectedJoinSchema.addColumn("name", Type.TEXT);
    expectedJoinSchema.addColumn("deptName", Type.TEXT);
    expectedJoinSchema.addColumn("score", Type.INT4);
  }

  @Test
  public final void testNaturalJoinPlan() {
    // two relations
    Expr context = sqlAnalyzer.parse(JOINS[0]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertSchema(expectedJoinSchema, plan.getOutSchema());

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode proj = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.JOIN, proj.getSubNode().getType());
    JoinNode join = (JoinNode) proj.getSubNode();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    assertTrue(join.hasJoinQual());
    ScanNode scan = (ScanNode) join.getInnerNode();
    assertEquals("score", scan.getTableId());

    assertEquals(ExprType.JOIN, join.getOuterNode().getType());
    join = (JoinNode) join.getOuterNode();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getOuterNode().getType());
    ScanNode outer = (ScanNode) join.getOuterNode();
    assertEquals("employee", outer.getTableId());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    ScanNode inner = (ScanNode) join.getInnerNode();
    assertEquals("dept", inner.getTableId());

    /*
    LogicalNode optimized = LogicalOptimizer.optimize(context, plan);
    assertSchema(expectedJoinSchema, optimized.getOutSchema());
    */
  }

  @Test
  public final void testInnerJoinPlan() {
    // two relations
    Expr expr = sqlAnalyzer.parse(JOINS[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertSchema(expectedJoinSchema, plan.getOutSchema());

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode proj = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.JOIN, proj.getSubNode().getType());
    JoinNode join = (JoinNode) proj.getSubNode();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    ScanNode scan = (ScanNode) join.getInnerNode();
    assertEquals("score", scan.getTableId());

    assertEquals(ExprType.JOIN, join.getOuterNode().getType());
    join = (JoinNode) join.getOuterNode();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getOuterNode().getType());
    ScanNode outer = (ScanNode) join.getOuterNode();
    assertEquals("employee", outer.getTableId());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    ScanNode inner = (ScanNode) join.getInnerNode();
    assertEquals("dept", inner.getTableId());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
    /*
    LogicalNode optimized = LogicalOptimizer.optimize(expr, plan);
    assertSchema(expectedJoinSchema, optimized.getOutSchema());
    */
  }

  @Test
  public final void testOuterJoinPlan() {
    // two relations
    Expr expr = sqlAnalyzer.parse(JOINS[2]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertSchema(expectedJoinSchema, plan.getOutSchema());

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode proj = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.JOIN, proj.getSubNode().getType());
    JoinNode join = (JoinNode) proj.getSubNode();
    assertEquals(JoinType.RIGHT_OUTER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    ScanNode scan = (ScanNode) join.getInnerNode();
    assertEquals("score", scan.getTableId());

    assertEquals(ExprType.JOIN, join.getOuterNode().getType());
    join = (JoinNode) join.getOuterNode();
    assertEquals(JoinType.LEFT_OUTER, join.getJoinType());
    assertEquals(ExprType.SCAN, join.getOuterNode().getType());
    ScanNode outer = (ScanNode) join.getOuterNode();
    assertEquals("employee", outer.getTableId());
    assertEquals(ExprType.SCAN, join.getInnerNode().getType());
    ScanNode inner = (ScanNode) join.getInnerNode();
    assertEquals("dept", inner.getTableId());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());

    /*
    LogicalNode optimized = LogicalOptimizer.optimize(context, plan);
    assertSchema(expectedJoinSchema, optimized.getOutSchema());
    */
  }


  @Test
  public final void testGroupby() throws CloneNotSupportedException {
    // without 'having clause'
    Expr context = sqlAnalyzer.parse(QUERIES[7]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    testQuery7(root.getSubNode());

    // with having clause
    context = sqlAnalyzer.parse(QUERIES[3]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(ExprType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.GROUP_BY, projNode.getSubNode().getType());
    GroupbyNode groupByNode = (GroupbyNode) projNode.getSubNode();

    assertEquals(ExprType.JOIN, groupByNode.getSubNode().getType());
    JoinNode joinNode = (JoinNode) groupByNode.getSubNode();

    assertEquals(ExprType.SCAN, joinNode.getOuterNode().getType());
    ScanNode leftNode = (ScanNode) joinNode.getOuterNode();
    assertEquals("dept", leftNode.getTableId());
    assertEquals(ExprType.SCAN, joinNode.getInnerNode().getType());
    ScanNode rightNode = (ScanNode) joinNode.getInnerNode();
    assertEquals("score", rightNode.getTableId());

    //LogicalOptimizer.optimize(context, plan);
  }


  @Test
  public final void testMultipleJoin() throws IOException {
    Expr expr = sqlAnalyzer.parse(
        FileUtil.readTextFile(new File("src/test/queries/tpch_q2_simplified.tql")));
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    Schema expected = tpch.getOutSchema("q2");
    assertSchema(expected, plan.getOutSchema());
//    LogicalNode optimized = LogicalOptimizer.optimize(context, plan);
//    System.out.println(optimized);
//    assertSchema(expected, optimized.getOutSchema());
  }


  static void testQuery7(LogicalNode plan) {
    assertEquals(ExprType.PROJECTION, plan.getType());
    ProjectionNode projNode = (ProjectionNode) plan;
    assertEquals(ExprType.GROUP_BY, projNode.getSubNode().getType());
    GroupbyNode groupByNode = (GroupbyNode) projNode.getSubNode();

    assertEquals(ExprType.JOIN, groupByNode.getSubNode().getType());
    JoinNode joinNode = (JoinNode) groupByNode.getSubNode();

    assertEquals(ExprType.SCAN, joinNode.getOuterNode().getType());
    ScanNode leftNode = (ScanNode) joinNode.getOuterNode();
    assertEquals("dept", leftNode.getTableId());
    assertEquals(ExprType.SCAN, joinNode.getInnerNode().getType());
    ScanNode rightNode = (ScanNode) joinNode.getInnerNode();
    assertEquals("score", rightNode.getTableId());
  }


  @Test
  public final void testStoreTable() throws CloneNotSupportedException {
    Expr context = sqlAnalyzer.parse(QUERIES[8]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    TestLogicalNode.testCloneLogicalNode(plan);
    testJsonSerDerObject(plan);

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(ExprType.STORE, root.getSubNode().getType());
    StoreTableNode storeNode = (StoreTableNode) root.getSubNode();
    testQuery7(storeNode.getSubNode());
    //LogicalOptimizer.optimize(context, plan);
  }

  @Test
  public final void testOrderBy() throws CloneNotSupportedException {
    Expr expr = sqlAnalyzer.parse(QUERIES[4]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();

    assertEquals(ExprType.SORT, projNode.getSubNode().getType());
    SortNode sortNode = (SortNode) projNode.getSubNode();

    assertEquals(ExprType.JOIN, sortNode.getSubNode().getType());
    JoinNode joinNode = (JoinNode) sortNode.getSubNode();

    assertEquals(ExprType.SCAN, joinNode.getOuterNode().getType());
    ScanNode leftNode = (ScanNode) joinNode.getOuterNode();
    assertEquals("dept", leftNode.getTableId());
    assertEquals(ExprType.SCAN, joinNode.getInnerNode().getType());
    ScanNode rightNode = (ScanNode) joinNode.getInnerNode();
    assertEquals("score", rightNode.getTableId());
  }

  @Test
  public final void testLimit() throws CloneNotSupportedException {
    Expr expr = sqlAnalyzer.parse(QUERIES[12]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;

    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();

    assertEquals(ExprType.LIMIT, projNode.getSubNode().getType());
    LimitNode limitNode = (LimitNode) projNode.getSubNode();

    assertEquals(ExprType.SORT, limitNode.getSubNode().getType());
  }

  @Test
  public final void testSPJPush() throws CloneNotSupportedException {
    Expr expr = sqlAnalyzer.parse(QUERIES[5]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);

    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.SELECTION, projNode.getSubNode().getType());
    SelectionNode selNode = (SelectionNode) projNode.getSubNode();
    assertEquals(ExprType.SCAN, selNode.getSubNode().getType());
    ScanNode scanNode = (ScanNode) selNode.getSubNode();
    assertEquals(scanNode.getTableId(), "employee");

    /*
    LogicalNode optimized = LogicalOptimizer.optimize(expr, plan);
    assertEquals(ExprType.ROOT, optimized.getType());
    root = (LogicalRootNode) optimized;

    assertEquals(ExprType.SCAN, root.getSubNode().getType());
    scanNode = (ScanNode) root.getSubNode();
    assertEquals("employee", scanNode.getTableId());*/
  }



  @Test
  public final void testSPJ() throws CloneNotSupportedException {
    Expr expr = sqlAnalyzer.parse(QUERIES[6]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    TestLogicalNode.testCloneLogicalNode(plan);
  }

  @Test
  public final void testJson() {
	  Expr expr = sqlAnalyzer.parse(QUERIES[9]);
	  LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);

	  String json = plan.toJson();
	  LogicalNode fromJson = CoreGsonHelper.fromJson(json, LogicalNode.class);
	  assertEquals(ExprType.ROOT, fromJson.getType());
	  LogicalNode groupby = ((LogicalRootNode)fromJson).getSubNode();
	  assertEquals(ExprType.PROJECTION, groupby.getType());
	  LogicalNode projNode = ((ProjectionNode)groupby).getSubNode();
	  assertEquals(ExprType.GROUP_BY, projNode.getType());
	  LogicalNode scan = ((GroupbyNode)projNode).getSubNode();
	  assertEquals(ExprType.SCAN, scan.getType());
  }

  @Test
  public final void testVisitor() {
    // two relations
    Expr expr = sqlAnalyzer.parse(QUERIES[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();

    TestVisitor vis = new TestVisitor();
    plan.postOrder(vis);

    assertEquals(ExprType.ROOT, vis.stack.pop().getType());
    assertEquals(ExprType.PROJECTION, vis.stack.pop().getType());
    assertEquals(ExprType.JOIN, vis.stack.pop().getType());
    assertEquals(ExprType.SCAN, vis.stack.pop().getType());
    assertEquals(ExprType.SCAN, vis.stack.pop().getType());
  }

  private static class TestVisitor implements LogicalNodeVisitor {
    Stack<LogicalNode> stack = new Stack<LogicalNode>();
    @Override
    public void visit(LogicalNode node) {
      stack.push(node);
    }
  }


  @Test
  public final void testExprNode() {
    Expr expr = sqlAnalyzer.parse(QUERIES[10]);
    LogicalPlan rootNode = planner.createPlan(expr);
    LogicalNode plan = rootNode.getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.EXPRS, root.getSubNode().getType());
    Schema out = root.getOutSchema();

    Iterator<Column> it = out.getColumns().iterator();
    Column col = it.next();
    assertEquals("res1", col.getColumnName());
    col = it.next();
    assertEquals("res2", col.getColumnName());
    col = it.next();
    assertEquals("res3", col.getColumnName());
  }

  static final String ALIAS [] = {
    "select deptName, sum(score) as total from score group by deptName",
    "select em.empId as id, sum(score) as total from employee as em inner join score using (em.deptName)"
  };


  @Test
  public final void testAlias1() {
    Expr expr = sqlAnalyzer.parse(ALIAS[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    Schema finalSchema = root.getOutSchema();
    Iterator<Column> it = finalSchema.getColumns().iterator();
    Column col = it.next();
    assertEquals("deptname", col.getColumnName());
    col = it.next();
    assertEquals("total", col.getColumnName());

    expr = sqlAnalyzer.parse(ALIAS[1]);
    plan = planner.createPlan(expr).getRootBlock().getRoot();
//    plan = LogicalOptimizer.optimize(expr, plan);
    root = (LogicalRootNode) plan;

    finalSchema = root.getOutSchema();
    it = finalSchema.getColumns().iterator();
    col = it.next();
    assertEquals("id", col.getColumnName());
    col = it.next();
    assertEquals("total", col.getColumnName());
  }

  @Test
  public final void testAlias2() {
    Expr expr = sqlAnalyzer.parse(ALIAS[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);

    Schema finalSchema = root.getOutSchema();
    Iterator<Column> it = finalSchema.getColumns().iterator();
    Column col = it.next();
    assertEquals("id", col.getColumnName());
    col = it.next();
    assertEquals("total", col.getColumnName());
  }

  static final String CREATE_TABLE [] = {
    "create external table table1 (name text, age int, earn bigint, score real) using csv with ('csv.delimiter'='|') location '/tmp/data'"
  };

  @Test
  public final void testCreateTableDef() {
    Expr expr = sqlAnalyzer.parse(CREATE_TABLE[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    LogicalRootNode root = (LogicalRootNode) plan;
    testJsonSerDerObject(root);
    assertEquals(ExprType.CREATE_TABLE, root.getSubNode().getType());
    CreateTableNode createTable = (CreateTableNode) root.getSubNode();

    Schema def = createTable.getSchema();
    assertEquals("name", def.getColumn(0).getColumnName());
    assertEquals(Type.TEXT, def.getColumn(0).getDataType().getType());
    assertEquals("age", def.getColumn(1).getColumnName());
    assertEquals(Type.INT4, def.getColumn(1).getDataType().getType());
    assertEquals("earn", def.getColumn(2).getColumnName());
    assertEquals(Type.INT8, def.getColumn(2).getDataType().getType());
    assertEquals("score", def.getColumn(3).getColumnName());
    assertEquals(Type.FLOAT4, def.getColumn(3).getDataType().getType());
    assertEquals(StoreType.CSV, createTable.getStorageType());
    assertEquals("/tmp/data", createTable.getPath().toString());
    assertTrue(createTable.hasOptions());
    assertEquals("|", createTable.getOptions().get("csv.delimiter"));
  }

  private static final List<Set<Column>> testGenerateCuboidsResult
    = Lists.newArrayList();
  private static final int numCubeColumns = 3;
  private static final Column [] testGenerateCuboids = new Column[numCubeColumns];

  private static final List<Set<Column>> testCubeByResult
    = Lists.newArrayList();
  private static final Column [] testCubeByCuboids = new Column[2];
  static {
    testGenerateCuboids[0] = new Column("col1", Type.INT4);
    testGenerateCuboids[1] = new Column("col2", Type.INT8);
    testGenerateCuboids[2] = new Column("col3", Type.FLOAT4);

    testGenerateCuboidsResult.add(new HashSet<Column>());
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[1]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[1]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[1],
        testGenerateCuboids[2]));
    testGenerateCuboidsResult.add(Sets.newHashSet(testGenerateCuboids[0],
        testGenerateCuboids[1], testGenerateCuboids[2]));

    testCubeByCuboids[0] = new Column("employee.name", Type.TEXT);
    testCubeByCuboids[1] = new Column("employee.empid", Type.INT4);
    testCubeByResult.add(new HashSet<Column>());
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[0]));
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[1]));
    testCubeByResult.add(Sets.newHashSet(testCubeByCuboids[0],
        testCubeByCuboids[1]));
  }

  @Test
  public final void testGenerateCuboids() {
    Column [] columns = new Column[3];

    columns[0] = new Column("col1", Type.INT4);
    columns[1] = new Column("col2", Type.INT8);
    columns[2] = new Column("col3", Type.FLOAT4);

    List<Column[]> cube = LogicalPlanner.generateCuboids(columns);
    assertEquals(((int)Math.pow(2, numCubeColumns)), cube.size());

    Set<Set<Column>> cuboids = Sets.newHashSet();
    for (Column [] cols : cube) {
      cuboids.add(Sets.newHashSet(cols));
    }

    for (Set<Column> result : testGenerateCuboidsResult) {
      assertTrue(cuboids.contains(result));
    }
  }

  static final String CUBE_ROLLUP [] = {
    "select name, empid, sum(score) from employee natural join score group by cube(name, empid)"
  };

  @Test
  public final void testCubeBy() {
    Expr expr = sqlAnalyzer.parse(CUBE_ROLLUP[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);

    Set<Set<Column>> cuboids = Sets.newHashSet();

    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.UNION, projNode.getSubNode().getType());
    UnionNode u0 = (UnionNode) projNode.getSubNode();
    assertEquals(ExprType.GROUP_BY, u0.getOuterNode().getType());
    assertEquals(ExprType.UNION, u0.getInnerNode().getType());
    GroupbyNode grp = (GroupbyNode) u0.getOuterNode();
    cuboids.add(Sets.newHashSet(grp.getGroupingColumns()));

    UnionNode u1 = (UnionNode) u0.getInnerNode();
    assertEquals(ExprType.GROUP_BY, u1.getOuterNode().getType());
    assertEquals(ExprType.UNION, u1.getInnerNode().getType());
    grp = (GroupbyNode) u1.getOuterNode();
    cuboids.add(Sets.newHashSet(grp.getGroupingColumns()));

    UnionNode u2 = (UnionNode) u1.getInnerNode();
    assertEquals(ExprType.GROUP_BY, u2.getOuterNode().getType());
    grp = (GroupbyNode) u2.getInnerNode();
    cuboids.add(Sets.newHashSet(grp.getGroupingColumns()));
    assertEquals(ExprType.GROUP_BY, u2.getInnerNode().getType());
    grp = (GroupbyNode) u2.getOuterNode();
    cuboids.add(Sets.newHashSet(grp.getGroupingColumns()));

    assertEquals((int)Math.pow(2, 2), cuboids.size());
    for (Set<Column> result : testCubeByResult) {
      assertTrue(cuboids.contains(result));
    }
  }


  static final String setStatements [] = {
    "select deptName from employee where deptName like 'data%' union select deptName from score where deptName like 'data%'",
    "select deptName from employee union select deptName from score as s1 intersect select deptName from score as s2",
    "select deptName from employee union select deptName from score as s1 except select deptName from score as s2 intersect select deptName from score as s3"
  };

  @Test
  public final void testSetPlan() {
    Expr expr = sqlAnalyzer.parse(setStatements[0]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.UNION, root.getSubNode().getType());
    UnionNode union = (UnionNode) root.getSubNode();
    assertEquals(ExprType.PROJECTION, union.getOuterNode().getType());
    ProjectionNode projL = (ProjectionNode) union.getOuterNode();
    assertEquals(ExprType.SELECTION, projL.getSubNode().getType());
    assertEquals(ExprType.PROJECTION, union.getInnerNode().getType());
    ProjectionNode projR = (ProjectionNode) union.getInnerNode();
    assertEquals(ExprType.SELECTION, projR.getSubNode().getType());
  }

  @Test
  public final void testSetPlan2() {
    // for testing multiple set statements
    Expr expr = sqlAnalyzer.parse(setStatements[1]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.UNION, root.getSubNode().getType());
    UnionNode union = (UnionNode) root.getSubNode();
    assertEquals(ExprType.PROJECTION, union.getOuterNode().getType());
    assertEquals(ExprType.INTERSECT, union.getInnerNode().getType());
    IntersectNode intersect = (IntersectNode) union.getInnerNode();
    assertEquals(ExprType.PROJECTION, intersect.getOuterNode().getType());
    assertEquals(ExprType.PROJECTION, intersect.getInnerNode().getType());
  }

  @Test
  public final void testSetPlan3() {
    // for testing multiple set statements
    Expr expr = sqlAnalyzer.parse(setStatements[2]);
    LogicalNode plan = planner.createPlan(expr).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.EXCEPT, root.getSubNode().getType());
    ExceptNode except = (ExceptNode) root.getSubNode();
    assertEquals(ExprType.UNION, except.getOuterNode().getType());
    assertEquals(ExprType.INTERSECT, except.getInnerNode().getType());
    UnionNode union = (UnionNode) except.getOuterNode();
    assertEquals(ExprType.PROJECTION, union.getOuterNode().getType());
    assertEquals(ExprType.PROJECTION, union.getInnerNode().getType());
    IntersectNode intersect = (IntersectNode) except.getInnerNode();
    assertEquals(ExprType.PROJECTION, intersect.getOuterNode().getType());
    assertEquals(ExprType.PROJECTION, intersect.getInnerNode().getType());
  }



  static final String [] setQualifiers = {
    "select name, empid from employee",
    "select distinct name, empid from employee",
    "select all name, empid from employee",
  };

  @Test
  public void testSetQualifier() {
    Expr context = sqlAnalyzer.parse(setQualifiers[0]);
    LogicalNode plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    LogicalRootNode root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    ProjectionNode projectionNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.SCAN, projectionNode.getSubNode().getType());

    context = sqlAnalyzer.parse(setQualifiers[1]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    assertEquals(ExprType.ROOT, plan.getType());
    root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    projectionNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.GROUP_BY, projectionNode.getSubNode().getType());

    context = sqlAnalyzer.parse(setQualifiers[2]);
    plan = planner.createPlan(context).getRootBlock().getRoot();
    testJsonSerDerObject(plan);
    root = (LogicalRootNode) plan;
    assertEquals(ExprType.PROJECTION, root.getSubNode().getType());
    projectionNode = (ProjectionNode) root.getSubNode();
    assertEquals(ExprType.SCAN, projectionNode.getSubNode().getType());
  }

  public void testJsonSerDerObject(LogicalNode rootNode) {
    String json = rootNode.toJson();
    LogicalNode fromJson = CoreGsonHelper.fromJson(json, LogicalNode.class);
    assertEquals("JSON (de) serialization equivalence check", rootNode, fromJson);
  }
}