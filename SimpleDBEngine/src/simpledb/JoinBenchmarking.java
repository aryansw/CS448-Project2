package simpledb;

import simpledb.buffer.BufferMgr;
import simpledb.jdbc.embedded.EmbeddedDriver;
import simpledb.opt.TablePlanner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;

public class JoinBenchmarking {

    static boolean disableIndexing = false;
    static boolean lessThan = false;
    static int recordsCount = 0;
    static ResultSet rs;
    static String query = null;
    static HashMap<String, Integer> matches = new HashMap<>();
    static ArrayList<String> searchkeys = new ArrayList<>();

    public static int indexBlocksAccessed = 0;
    public static int mergeBlocksAccessed = 0;
    public static int productBlocksAccessed = 0;
    public static int nestedBlocksAccessed = 0;

    public static long joinTableTest(int n) throws SQLException {
        EmbeddedDriver d = new EmbeddedDriver();
        String url = "jdbc:simpledb:studentdb" + UUID.randomUUID().toString().substring(9); //Makes new database each time
        Connection conn = d.connect(url, null);
        Statement stmt = conn.createStatement();

        /* TABLE BUILDING */
        String s = "create table STUDENT(SId int, SFirstName varchar(40), " +
                "SLastName varchar(40), MajorId int, GradYear int)";
        stmt.executeUpdate(s);
        System.out.println("Table STUDENT created.");

        ArrayList<Name> names = Name.generateNames(n);

        s = "insert into STUDENT(SId, SFirstName, SLastName, MajorId, GradYear) values ";
        String[] studvals = new String[n];

        for (int i = 0; i < n; i++) {
            studvals[i] = String.format("(%d, '%s', '%s', %d, %d)", (i + 1), names.get(i).firstName,
                    names.get(i).lastName, rand.nextInt(courseNames.length), randomGradYear());
        }
        for (String studval : studvals) stmt.executeUpdate(s + studval);
        System.out.println("STUDENT records inserted.");

        if (!disableIndexing) {
            // Create an index on the student major id
            s = "create index smid on STUDENT(MajorId)";
            stmt.executeUpdate(s);
        }

        s = "create table MAJOR(MId int, MajorName varchar(40), MajorAbbr varchar(5))";
        stmt.executeUpdate(s);
        System.out.println("Table MAJOR created.");

        s = "insert into MAJOR(MId, MajorName, MajorAbbr) values ";
        String[] majorvals = new String[courseNames.length];
        for (int i = 0; i < courseNames.length; i++) {
            majorvals[i] = String.format("(%d, '%s', '%s')", i, courseNames[i], courseAbs[i]);
        }

        for (String majorval : majorvals) stmt.executeUpdate(s + majorval);
        System.out.println("MAJOR records inserted.");

        if (!disableIndexing) {
            // Create an index on the major id
            s = "create index mid on MAJOR(MId)";
            stmt.executeUpdate(s);
        }

        /* RUNNING JOIN QUERY */
        if (lessThan) {
            s = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                    "from STUDENT, MAJOR " +
                    "where MId < MajorId";
        } else {
            s = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                    "from STUDENT, MAJOR " +
                    "where MId = MajorId";
        }

        if (query != null) {
            s = query;
        }

        BufferMgr.hits = 0;
        BufferMgr.misses = 0;
        long time = System.currentTimeMillis();
        rs = stmt.executeQuery(s);
        int count = 0;
        while (rs.next()) {
            count++;
            String result = rs.getInt("SId") + rs.getString("SFirstName") +
                    rs.getString("SLastName") + rs.getInt("MId") +
                    rs.getString("MajorName") + rs.getString("MajorAbbr");
            matches.put(result, matches.getOrDefault(result, 0) + 1);
            searchkeys.add(result);
        } // Going through entire result set.
        recordsCount = count;

        System.out.println("Number of records in join: " + count);
        conn.close();
        return System.currentTimeMillis() - time;
    }

    private static String runTest(int n, int type) {
        TablePlanner.DEBUG_MODE = true;
        TablePlanner.MODE = type;
        String test = "";
        String result = "";
        switch (type) {
            case 1:
                test = "Index Join";
                break;
            case 2:
                test = "Block Nested Loop Join";
                break;
            case 3:
                test = "Multi Buffer Product and Select";
                break;
            case 4:
                test = "Merge Join";
                break;
        }
        try {
            String csvForm = "";
            long time = joinTableTest(n);
            result += (test + " on input size: " + n + "\n");
            result += (test + " time taken: " + time) + "\n";
            result += (test + " guess for block accesses: " + TablePlanner.DEBUG_PLAN.blocksAccessed() + "\n");
            result += (test + " records: " + recordsCount) + "\n";
            result += (test + " hits: " + BufferMgr.hits) + "\n";
            result += (test + " misses (disk reads): " + BufferMgr.misses) + "\n" + "\n";
            if (TablePlanner.MODE != type) {
                return test + " mode was not possible.\n\n";
            }
            csvForm += n + "," + test + "," + time + "," + TablePlanner.DEBUG_PLAN.blocksAccessed() + "," +
                    recordsCount + "," + BufferMgr.hits + "," + BufferMgr.misses;
            return result;
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) throws SQLException {
//        runTests();
//        correctnessTest();
        testSelection();
    }

    /* Tests if the correct join algorithm is chosen */
    public static void testSelection() throws SQLException {
        TablePlanner.DEBUG_MODE = false;

        String out = "\n\n";
        query = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                "from STUDENT, MAJOR " +
                "where MId = MajorId";
        out += "Running an equals join query" + "\n";
        joinTableTest(200);
        out += getBlocksAccessed();
        out += "\n\n";

        query = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                "from STUDENT, MAJOR " +
                "where SId=MId";
        out += "Running a non-indexed equals than join query" + "\n";
        joinTableTest(200);
        out += getBlocksAccessed();
        out += "\n\n";

        query = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                "from STUDENT, MAJOR " +
                "where MId = MajorId and SId < 140";
        out += "Running an equals join with multiple conditions query" + "\n";
        joinTableTest(200);
        out += getBlocksAccessed();
        out += "\n\n";

        query = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                "from STUDENT, MAJOR " +
                "where MId < MajorId";
        out += "Running a less than join query" + "\n";
        joinTableTest(200);
        out += getBlocksAccessed();
        out += "\n\n";

        query = "select SId, SFirstName, SLastName, MId, MajorName, MajorAbbr " +
                "from STUDENT, MAJOR " +
                "where MId < MajorId and SId = 140";
        out += "Running a less than with multiple conditions query" + "\n";
        joinTableTest(200);
        out += getBlocksAccessed();
        out += "\n\n";

        System.out.println(out);
    }

    /* Tests if all joins give the same result */
    private static void correctnessTest() throws SQLException {
        if (!correctnessEqualsTest()) {
            System.out.println("Correctness test failed for Equal Join");
            return;
        }
        if (!correctnessLessThanTest()) {
            System.out.println("Correctness test failed for Less Than Join");
            return;
        }
        System.out.println("Both tests passed!");
    }

    /* Run tests to see performance of all join types */
    private static void runTests() {
        int[] sizes = new int[]{200, 600, 1000, 1400, 2000};
        StringBuilder s1 = new StringBuilder();
        for (int size : sizes) {
            for (int j = 1; j <= 4; j++) {
                s1.append(runTest(size, j));
            }
        }
        System.out.println("\n\n---FINAL RESULTS---");
        System.out.print(s1);
    }

    private static boolean correctnessEqualsTest() throws SQLException {
        matches = new HashMap<>();
        searchkeys = new ArrayList<>(); // Reset these to empty.

        runTest(200, 2); // Block Nested Loop Join
        runTest(200, 3); // Multi Buffer Product and Select
        runTest(200, 4); // Merge Join

        int count = 3; // All the three joins should have the same results => Same tuples
        for (String key : searchkeys) {
            if (matches.get(key) != count) { // Check if all the results are the same
                System.out.println("Incorrect results for " + key);
                return false;
            }
        }
        System.out.println("All Equal Join tests passed.");
        return true;
    }

    private static boolean correctnessLessThanTest() throws SQLException {
        matches = new HashMap<>();
        searchkeys = new ArrayList<>(); // Reset these to empty.

        lessThan = true;
        runTest(200, 2); // Block Nested Loop Join
        runTest(200, 3); // Multi Buffer Product and Select
        lessThan = false;

        int count = 2; // All the three joins should have the same results => Same tuples
        for (String key : searchkeys) {
            if (matches.get(key) != count) { // Check if all the results are the same
                System.out.println("Incorrect results for " + key);
                return false;
            }
        }
        System.out.println("All Less Than tests passed.");
        return true;
    }

    private static void writeToFile() {
        // Change the runTest method to return csv form for this to work
        int[] sizes = new int[]{200, 600, 1000, 1400, 2000};
        PrintWriter pw;
        try {
            pw = new PrintWriter("resultsEquals.csv");
            pw.println("Input Size,Join Algorithm,Runtime,Block Guess,Records Count,Hits,Misses");
            for (int size : sizes) {
                for (int j = 1; j <= 4; j++) {
                    String s1 = runTest(size, j);
                    if (s1 != null)
                        pw.println(s1);
                }
            }
            pw.close();
            lessThan = true;
            pw = new PrintWriter("resultsLessThan.csv");
            pw.println("Input Size,Join Algorithm,Runtime,Block Guess,Records Count,Hits,Misses");
            for (int size : sizes) {
                for (int j = 1; j <= 4; j++) {
                    String s1 = runTest(size, j);
                    if (s1 != null)
                        pw.println(s1);
                }
            }
            pw.close();
            lessThan = false;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static String getBlocksAccessed(){
        String out = "";
        out += "    Number of Blocks Accessed in Product Join: " + productBlocksAccessed + "\n";
        out += "    Number of Blocks Accessed in Block Nested Join: " + nestedBlocksAccessed + "\n";
        if (indexBlocksAccessed >= 0){
            out += "    Number of Blocks Accessed in Index Join: " + indexBlocksAccessed + "\n";
        } else {
            out += "    Index Join was not possible\n";
        }
        if (mergeBlocksAccessed >= 0){
            out += "    Number of Blocks Accessed in Merge Join: " + mergeBlocksAccessed + "\n";
        } else {
            out += "    Merge Join was not possible\n";
        }
        String test = "";
        switch (TablePlanner.MODE) {
            case 1:
                test = "Index";
                break;
            case 2:
                test = "Block Nested";
                break;
            case 3:
                test = "Product";
                break;
            case 4:
                test = "Merge";
                break;
        }
        out += "    " + test + " join was selected\n";
        return out;
    }

    static Random rand = new Random(42); // For number generation


    private static int randomGradYear() {
        return 2021 + rand.nextInt(4);
    }

    private static final String[] courseNames = new String[]{"Computer Science", "Chemical Engineering",
            "Mechanical Engineering", "Aerospace Engineering"};

    private static final String[] courseAbs = new String[]{"CS", "CHE", "ME", "ASE"};

    private static class Name {
        String firstName;
        String lastName;

        public Name(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Name name = (Name) o;
            return Objects.equals(firstName, name.firstName) && Objects.equals(lastName, name.lastName);
        }

        public static ArrayList<Name> generateNames(int n) {
            rand = new Random(42); // Fix the seed
            ArrayList<Name> names = new ArrayList<>();
            int c1 = 0;
            int c2 = 0;
            n = Math.min(n, firstNameList.length * lastNameList.length);
            for (int i = 0; i < n; i++) {
                Name name = new Name(firstNameList[rand.nextInt(firstNameList.length)],
                        lastNameList[rand.nextInt(lastNameList.length)]);
                while (names.contains(name)) {
                    name = new Name(firstNameList[rand.nextInt(firstNameList.length)],
                            lastNameList[rand.nextInt(lastNameList.length)]);
                }
                names.add(name);
            }
            return names;
        }

        static final String[] firstNameList = new String[]{"Adam", "Adrian", "Alan", "Alexander", "Andrew", "Anthony", "Austin",
                "Benjamin", "Blake", "Boris", "Brandon", "Brian", "Cameron", "Carl", "Charles", "Christian", "Christopher",
                "Colin", "Connor", "Dan", "David", "Dominic", "Dylan", "Edward", "Eric", "Evan", "Frank", "Gavin", "Gordon",
                "Harry", "Ian", "Isaac", "Jack", "Jacob", "Jake", "James", "Jason", "Joe", "John", "Jonathan", "Joseph",
                "Joshua", "Julian", "Justin", "Keith", "Kevin", "Leonard", "Liam", "Lucas", "Luke", "Matt", "Max",
                "Michael", "Nathan", "Neil", "Nicholas", "Oliver", "Owen", "Paul", "Peter", "Phil", "Piers",
                "Richard", "Robert", "Ryan", "Sam", "Sean", "Sebastian", "Simon", "Stephen", "Steven",
                "Stewart", "Thomas", "Tim", "Trevor", "Victor", "Warren", "William"};

        static final String[] lastNameList = new String[]{"Abraham", "Allan", "Alsop", "Anderson", "Arnold", "Avery", "Bailey",
                "Baker", "Ball", "Bell", "Berry", "Black", "Blake", "Bond", "Bower", "Brown", "Buckland", "Burgess",
                "Butler", "Cameron", "Campbell", "Carr", "Chapman", "Churchill", "Clark", "Clarkson", "Coleman", "Cornish",
                "Davidson", "Davies", "Dickens", "Dowd", "Duncan", "Dyer", "Edmunds", "Ellison", "Ferguson", "Fisher",
                "Forsyth", "Fraser", "Gibson", "Gill", "Glover", "Graham", "Grant", "Gray", "Greene", "Hamilton",
                "Hardacre", "Harris", "Hart", "Hemmings", "Henderson", "Hill", "Hodges", "Howard", "Hudson", "Hughes",
                "Hunter", "Ince", "Jackson", "James", "Johnston", "Jones", "Kelly", "Kerr", "King", "Knox", "Lambert",
                "Langdon", "Lawrence", "Lee", "Lewis", "Lyman", "MacDonald", "Mackay", "Mackenzie", "MacLeod", "Manning",
                "Marshall", "Martin", "Mathis", "May", "McDonald", "McLean", "McGrath", "Metcalfe", "Miller", "Mills",
                "Mitchell", "Morgan", "Morrison", "Murray", "Nash", "Newman", "Nolan", "North", "Ogden", "Oliver", "Paige",
                "Parr", "Parsons", "Paterson", "Payne", "Peake", "Peters", "Piper", "Poole", "Powell", "Pullman", "Quinn",
                "Rampling", "Randall", "Rees", "Reid", "Roberts", "Robertson", "Ross", "Russell", "Rutherford", "Sanderson",
                "Scott", "Sharp", "Short", "Simpson", "Skinner", "Slater", "Smith", "Springer", "Stewart", "Sutherland",
                "Taylor", "Terry", "Thomson", "Tucker", "Turner", "Underwood", "Vance", "Vaughan", "Walker", "Wallace",
                "Walsh", "Watson", "Welch", "White", "Wilkins", "Wilson", "Wright", "Young"};
    }
}
