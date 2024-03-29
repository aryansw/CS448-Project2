package simpledb.multibuffer.nestedblock;

import simpledb.materialize.MaterializePlan;
import simpledb.materialize.TempTable;
import simpledb.plan.Plan;
import simpledb.plan.ProductPlan;
import simpledb.query.Predicate;
import simpledb.query.Scan;
import simpledb.query.UpdateScan;
import simpledb.record.Schema;
import simpledb.record.TableScan;
import simpledb.tx.Transaction;

/**
 * The Plan class for the Nested Block Join .
 * @author Aryan Wadhwani
 */
public class NestedBlockJoinPlan implements Plan {
   private Transaction tx;
   private Plan lhs, rhs;
   private Schema schema = new Schema();
   private Predicate pred;
   private boolean isSwapped = false;

   /**
    * Creates a join plan for the specified queries.
    * @param lhs the plan for the LHS query
    * @param rhs the plan for the RHS query
    * @param tx the calling transaction
    * @param pred the predicate to apply to the join
    */
   public NestedBlockJoinPlan(Transaction tx, Plan lhs, Plan rhs, Predicate pred) {
      this.tx = tx;
      if (swapToOptimize(lhs, rhs)){
         Plan temp = lhs;
         lhs = rhs;
         rhs = temp;
         isSwapped = true;
         System.out.println("Swapped");
      }
      this.lhs = lhs;
      this.rhs = rhs;
      this.pred = pred;
      System.out.println(lhs.schema().fields());
      System.out.println(rhs.schema().fields());
      schema.addAll(lhs.schema());
      schema.addAll(rhs.schema());
   }

   // Swap the plans so that the smaller one is on the LHS
   private static boolean swapToOptimize(Plan p1, Plan p2) {
      Plan prod1 = new ProductPlan(p1, p2);
      Plan prod2 = new ProductPlan(p2, p1);
      int b1 = prod1.blocksAccessed();
      int b2 = prod2.blocksAccessed();
      return (b1 >= b2);
   }


   /**
    * A scan for this query is created and returned, as follows.
    * First, the method materializes its LHS and RHS queries.
    * It then determines the optimal chunk size,
    * based on the size of the materialized RHS file and the
    * number of available buffers.
    * It creates a chunk plan for each chunk, saving them in a list.
    * Finally, it creates a multiscan for this list of plans,
    * and returns that scan.
    * @see Plan#open()
    */
   public Scan open() {
      TableScan lhsTT;
      TableScan rhsTT;
      if (!isSwapped){
         lhsTT = (TableScan) copyRecordsFrom(lhs).open();
         rhsTT = (TableScan) rhs.open();
      } else {
        lhsTT = (TableScan) lhs.open();
        rhsTT = (TableScan) copyRecordsFrom(rhs).open();
      }
      return new NestedBlockJoinScan(tx, lhsTT, rhsTT, pred);
   }

   /**
    * Returns an estimate of the number of block accesses
    * required to execute the query.
    */
   public int blocksAccessed() {
      // Materialize gives number of blocks in the full table
      int lhsBlocks = lhs.blocksAccessed();
      if (lhsBlocks == 0){
         lhsBlocks = 1;
      }
      int rhsBlocks = new MaterializePlan(tx, rhs).blocksAccessed();
      return (lhsBlocks * rhsBlocks) + lhsBlocks;
   }

   /**
    * Estimates the number of output records in the product.
    * The formula is:
    * <pre> R(product(p1,p2)) = R(p1)*R(p2) </pre>
    * @see Plan#recordsOutput()
    */
   public int recordsOutput() {
      return (lhs.recordsOutput() * rhs.recordsOutput()) * pred.reductionFactor(this);
   }

   /**
    * Estimates the distinct number of field values in the product.
    * Since the product does not increase or decrease field values,
    * the estimate is the same as in the appropriate underlying query.
    * @see Plan#distinctValues(String)
    */
   public int distinctValues(String fldname) {
      if (lhs.schema().hasField(fldname))
         return lhs.distinctValues(fldname);
      else
         return rhs.distinctValues(fldname);
   }

   /**
    * Returns the schema of the product,
    * which is the union of the schemas of the underlying queries.
    * @see Plan#schema()
    */
   public Schema schema() {
      return schema;
   }

   private TempTable copyRecordsFrom(Plan p) {
      Scan   src = p.open();
      Schema sch = p.schema();
      TempTable t = new TempTable(tx, sch);
      UpdateScan dest = (UpdateScan) t.open();
      while (src.next()) {
         dest.insert();
         for (String fldname : sch.fields()) {
            dest.setVal(fldname, src.getVal(fldname));
         }
      }
      src.close();
      dest.close();
      return t;
   }
}
