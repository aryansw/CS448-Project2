package simpledb.opt;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

import simpledb.JoinBenchmarking;
import simpledb.materialize.MergeJoinPlan;
import simpledb.multibuffer.nestedblock.NestedBlockJoinPlan;
import simpledb.tx.Transaction;
import simpledb.record.*;
import simpledb.query.*;
import simpledb.metadata.*;
import simpledb.index.planner.*;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.plan.*;

/**
 * This class contains methods for planning a single table.
 * @author Edward Sciore
 */
public class TablePlanner {
   private TablePlan myplan;
   private Predicate mypred;
   private Schema myschema;
   private Map<String,IndexInfo> indexes;
   private Transaction tx;

   /**
    * Creates a new table planner.
    * The specified predicate applies to the entire query.
    * The table planner is responsible for determining
    * which portion of the predicate is useful to the table,
    * and when indexes are useful.
    * @param tblname the name of the table
    * @param mypred the query predicate
    * @param tx the calling transaction
    */
   public TablePlanner(String tblname, Predicate mypred, Transaction tx, MetadataMgr mdm) {
      this.mypred  = mypred;
      this.tx  = tx;
      myplan   = new TablePlan(tx, tblname, mdm);
      myschema = myplan.schema();
      indexes  = mdm.getIndexInfo(tblname, tx);
   }

   /**
    * Constructs a select plan for the table.
    * The plan will use an indexselect, if possible.
    * @return a select plan for the table.
    */
   public Plan makeSelectPlan() {
      Plan p = makeIndexSelect();
      if (p == null)
         p = myplan;
      return addSelectPred(p);
   }

   /**
    * Constructs a join plan of the specified plan
    * and the table.  The plan will use an indexjoin, if possible.
    * (Which means that if an indexselect is also possible,
    * the indexjoin operator takes precedence.)
    * The method returns null if no join is possible.
    * @param current the specified plan
    * @return a join plan of the plan and this table
    */

   public static boolean DEBUG_MODE = false;
   public static int MODE = 0;
   public static Plan DEBUG_PLAN = null;

   public Plan makeJoinPlan(Plan current) {
      Schema currsch = current.schema();
      Predicate joinpred = mypred.joinSubPred(myschema, currsch);
      if (joinpred == null)
         return null;

      if (!DEBUG_MODE) {
         // Select Plan p based on cost estimation for IndexJoin, MergeJoin and BlockNestedJoin in blocks accessed
         Plan p = makeProductJoin(current, currsch);
         JoinBenchmarking.productBlocksAccessed = p.blocksAccessed();
         TablePlanner.MODE = 3;
         Plan p1 = makeMergeJoin(current, currsch);
         if (p1 != null){
            JoinBenchmarking.mergeBlocksAccessed = p1.blocksAccessed();
         } else {
            JoinBenchmarking.mergeBlocksAccessed = -1;
         }
         if (p1 != null && p1.blocksAccessed() < p.blocksAccessed()){
            System.out.println("Merge Join");
            p = p1;
            TablePlanner.MODE = 4;
         }
         Plan p2 = makeNestedBlockJoin(current, currsch);
         if (p2.blocksAccessed() < p.blocksAccessed()){
            System.out.println("Nested Block Join");
            p = p2;
            TablePlanner.MODE = 2;
         }
         JoinBenchmarking.nestedBlocksAccessed = p2.blocksAccessed();
         Plan p3 = makeIndexJoin(current, currsch);
         if (p3 != null && p3.blocksAccessed() < p.blocksAccessed()){
            System.out.println("Index Join");
            p = p3;
            TablePlanner.MODE = 1;
         }
         if (p3 != null){
            JoinBenchmarking.indexBlocksAccessed = p3.blocksAccessed();
         } else {
            JoinBenchmarking.indexBlocksAccessed = -1;
         }
         if (TablePlanner.MODE == 1){
            System.out.println("Product Join");
         }
         return p;
      } else {
         switch (MODE){
            case 1:
               DEBUG_PLAN = makeIndexJoin(current, currsch);
               if (DEBUG_PLAN == null) {
                  MODE = 3;
                  System.out.println("Index Join failed.");
                  DEBUG_PLAN = makeProductJoin(current, currsch);
                  return DEBUG_PLAN;
               }
               return DEBUG_PLAN;
            case 2:
               DEBUG_PLAN = makeNestedBlockJoin(current, currsch);
               return DEBUG_PLAN;
            case 4:
               System.out.println("Merge Join");
               DEBUG_PLAN = makeMergeJoin(current, currsch);
               if (DEBUG_PLAN == null){
                  System.out.println("Merge Join failed.");
                  MODE = 3;
                  DEBUG_PLAN = makeProductJoin(current, currsch);
               }
               return DEBUG_PLAN;
            default:
               DEBUG_PLAN = makeProductJoin(current, currsch);
               return DEBUG_PLAN;
         }
      }
   }

   /**
    * Constructs a product plan of the specified plan and
    * this table.
    * @param current the specified plan
    * @return a product plan of the specified plan and this table
    */
   public Plan makeProductPlan(Plan current) {
      Plan p = addSelectPred(myplan);
      return new MultibufferProductPlan(tx, current, p);
   }

   private Plan makeIndexSelect() {
      for (String fldname : indexes.keySet()) {
         Constant val = mypred.equatesWithConstant(fldname);
         if (val != null) {
            IndexInfo ii = indexes.get(fldname);
            System.out.println("index on " + fldname + " used");
            return new IndexSelectPlan(myplan, ii, val);
         }
      }
      return null;
   }

   private Plan makeIndexJoin(Plan current, Schema currsch) {
      for (String fldname : indexes.keySet()) {
         String outerfield = mypred.equatesWithField(fldname);
         if (outerfield != null && currsch.hasField(outerfield)) {
            IndexInfo ii = indexes.get(fldname);
            Plan p = new IndexJoinPlan(current, myplan, ii, outerfield);
            p = addSelectPred(p);
            return addJoinPred(p, currsch);
         }
      }
      return null;
   }

   private Plan makeNestedBlockJoin(Plan current, Schema currsch) {
      return new NestedBlockJoinPlan(tx, current, myplan, mypred.joinSubPred(currsch, myschema));
   }

   private Plan makeMergeJoin(Plan current, Schema currsch) {
      List<Term> listOfTerms = mypred.getTerms();
      Plan p;
      for (int i = 0; i < listOfTerms.size(); i++) {
         Term term = listOfTerms.get(i);
         if (!term.isEqual())
            continue;
         if (term.getLhs().isFieldName() && term.getRhs().isFieldName()) {
            String field1 = term.getLhs().asFieldName();
            String field2 = term.getRhs().asFieldName();

            if (currsch.hasField(field2) && myschema.hasField(field1)) {
               p = new MergeJoinPlan(tx, current, myplan, field2, field1);
               p = addSelectPred(p);
               return addJoinPred(p, currsch);
            }
            else if (currsch.hasField(field1) && myschema.hasField(field2)) {
               p = new MergeJoinPlan(tx, current, myplan, field1, field2);
               p = addSelectPred(p);
               return addJoinPred(p, currsch);
            }
         }
      }

      return null;
   }

   private Plan makeProductJoin(Plan current, Schema currsch) {
      Plan p = makeProductPlan(current);
      return addJoinPred(p, currsch);
   }

   private Plan addSelectPred(Plan p) {
      Predicate selectpred = mypred.selectSubPred(myschema);
      if (selectpred != null)
         return new SelectPlan(p, selectpred);
      else
         return p;
   }

   private Plan addJoinPred(Plan p, Schema currsch) {
      Predicate joinpred = mypred.joinSubPred(currsch, myschema);
      if (joinpred != null)
         return new SelectPlan(p, joinpred);
      else
         return p;
   }
}
