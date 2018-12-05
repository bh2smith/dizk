package relations.r1cs;

import algebra.curves.barreto_naehrig.bn254a.BN254aFields;
import algebra.fields.AbstractFieldElementExpanded;
import configuration.Configuration;
import org.apache.spark.api.java.JavaPairRDD;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import relations.objects.*;
import scala.Tuple2;
import scala.Tuple3;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;


public class FileToR1CS {

    public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        Tuple3<R1CSRelation<FieldT>, Assignment<FieldT>, Assignment<FieldT>>
    serialR1CSFromJSON(String filePath) {
        JSONParser parser = new JSONParser();
        JSONObject jsonObject;
        JSONArray primaryInputs = new JSONArray();
        JSONArray auxInputs = new JSONArray();
        JSONArray constraintList = new JSONArray();

        try {
            Object obj = parser.parse(new FileReader(filePath));

            jsonObject = (JSONObject) obj;
            primaryInputs = (JSONArray) jsonObject.get("primary_input");
            auxInputs = (JSONArray) jsonObject.get("aux_input");
            constraintList = (JSONArray) jsonObject.get("constraints");

        } catch (Exception e) {
            e.printStackTrace();
        }

        final Assignment<FieldT> oneFullAssignment = new Assignment<>();

        int numInputs = primaryInputs.size();
        for (int i = 0; i < numInputs; i++) {
            final BN254aFields.BN254aFr value = new BN254aFields.BN254aFr((String) primaryInputs.get(i));
            oneFullAssignment.add((FieldT) value);
        }

        int numAuxiliary = auxInputs.size();
        for (int i = 0; i < numAuxiliary; i++) {
            final BN254aFields.BN254aFr value = new BN254aFields.BN254aFr((String) auxInputs.get(i));
            oneFullAssignment.add((FieldT) value);
        }

        int numConstraints = constraintList.size();
        JSONArray[] constraintArray = new JSONArray[numConstraints];
        for (int i = 0; i < numConstraints; i++) {
            constraintArray[i] = (JSONArray) constraintList.get(i);
        }

        final R1CSConstraints<FieldT> constraints = new R1CSConstraints<>();

        for (int i = 0; i < constraintArray.length; i++) {

            final LinearCombination<FieldT> A = JSONObjectToCombination(
                    (JSONObject) constraintArray[i].get(0));
            final LinearCombination<FieldT> B = JSONObjectToCombination(
                    (JSONObject) constraintArray[i].get(1));
            final LinearCombination<FieldT> C = JSONObjectToCombination(
                    (JSONObject) constraintArray[i].get(2));

            constraints.add(new R1CSConstraint<>(A, B, C));
        }

        final R1CSRelation<FieldT> r1cs = new R1CSRelation<>(constraints, numInputs, numAuxiliary);
        final Assignment<FieldT> primary = new Assignment<>(oneFullAssignment.subList(0, numInputs));
        final Assignment<FieldT> auxiliary = new Assignment<>(oneFullAssignment
                .subList(numInputs, oneFullAssignment.size()));

        assert (r1cs.numInputs() == numInputs);
        assert (r1cs.numVariables() >= numInputs);
        assert (r1cs.numVariables() == oneFullAssignment.size());
        assert (r1cs.numConstraints() == numConstraints);
        assert (r1cs.isSatisfied(primary, auxiliary));

        return new Tuple3<>(r1cs, primary, auxiliary);
    }

    public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        R1CSRelation<FieldT>
    serialR1CSFromText(String filePath) {

        final R1CSConstraints<FieldT> constraints = new R1CSConstraints<>();

        int numInputs = -1;
        int numAuxiliary = -1;

        try{
            String[] constraintParameters = new BufferedReader(
                    new FileReader(filePath + ".problem_size")).readLine().split(" ");

            numInputs = Integer.parseInt(constraintParameters[1]);
            numAuxiliary = Integer.parseInt(constraintParameters[2]);

            int numConstraints = Integer.parseInt(constraintParameters[2]);

            BufferedReader brA = new BufferedReader(new FileReader(filePath + ".a"));
            BufferedReader brB = new BufferedReader(new FileReader(filePath + ".b"));
            BufferedReader brC = new BufferedReader(new FileReader(filePath + ".c"));

            for (int currRow = 0; currRow < numConstraints; currRow++){
                LinearCombination<FieldT> A = makeRowAt(currRow, brA);
                LinearCombination<FieldT> B = makeRowAt(currRow, brB);
                LinearCombination<FieldT> C = makeRowAt(currRow, brC);

                constraints.add(new R1CSConstraint<>(A, B, C));
            }

            brA.close();
            brB.close();
            brC.close();

        } catch (Exception e){
            System.err.println("Error: " + e.getMessage());
        }

        return new R1CSRelation<>(constraints, numInputs, numAuxiliary);
    }

    public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        Tuple3<R1CSRelationRDD<FieldT>, Assignment<FieldT>, JavaPairRDD<Long, FieldT>>
    distributedR1CSFromJSON(
            final String filePath,
            final Configuration config) {

        JSONParser parser = new JSONParser();
        JSONObject jsonObject;
        JSONArray primaryInputs = new JSONArray();
        JSONArray auxInputs = new JSONArray();
        JSONArray constraintList = new JSONArray();

        try {
            Object obj = parser.parse(new FileReader(filePath));

            jsonObject = (JSONObject) obj;
            primaryInputs = (JSONArray) jsonObject.get("primary_input");
            auxInputs = (JSONArray) jsonObject.get("aux_input");
            constraintList = (JSONArray) jsonObject.get("constraints");

        } catch (Exception e) {
            e.printStackTrace();
        }

        // TODO - do we really need to load this into an ArrayList?
        int numConstraints = constraintList.size();
        JSONArray[] constraintArray = new JSONArray[numConstraints];
        for (int i = 0; i < numConstraints; i++) {
            constraintArray[i] = (JSONArray) constraintList.get(i);
        }

        final ArrayList<Integer> partitions = constructPartitionArray(config.numPartitions(), numConstraints);

        // Load Linear Combinations as RDD format
        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationA = distributedCombinationFromJSON(
                config, partitions, constraintArray, 0, numConstraints);

        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationB = distributedCombinationFromJSON(
                config, partitions, constraintArray, 1, numConstraints);

        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationC = distributedCombinationFromJSON(
                config, partitions, constraintArray, 2, numConstraints);

        // Serial assignment may not be necessary.
        final Assignment<FieldT> serialAssignment = new Assignment<>();
        int numInputs = primaryInputs.size();
        for (int i = 0; i < numInputs; i++) {
            final BN254aFields.BN254aFr value = new BN254aFields.BN254aFr((String) primaryInputs.get(i));
            serialAssignment.add((FieldT) value);
        }
        int numAuxiliary = auxInputs.size();
        for (int i = 0; i < numAuxiliary; i++) {
            final BN254aFields.BN254aFr value = new BN254aFields.BN254aFr((String) auxInputs.get(i));
            serialAssignment.add((FieldT) value);
        }

        final long numVariables = numInputs + numAuxiliary;
        final int numPartitions = config.numPartitions();

        final ArrayList<Integer> variablePartitions = constructPartitionArray(config.numPartitions(), numVariables);


        final int numExecutors = config.numExecutors();
        final ArrayList<Integer> assignmentPartitions = constructPartitionArray(numExecutors, numVariables);


        JavaPairRDD<Long, FieldT> oneFullAssignment = config.sparkContext()
                .parallelize(assignmentPartitions, numExecutors).flatMapToPair(part -> {
                    final long startIndex = part * (numVariables / numExecutors);
                    final long partSize = part == numExecutors ? numVariables %
                            (numVariables / numExecutors) : numVariables / numExecutors;

                    final ArrayList<Tuple2<Long, FieldT>> assignment = new ArrayList<>();
                    for (long i = startIndex; i < startIndex + partSize; i++) {
                        assignment.add(new Tuple2<>(i, serialAssignment.get((int) i)));
                    }
                    return assignment.iterator();
                }).persist(config.storageLevel());


        final Assignment<FieldT> primary = new Assignment<>(serialAssignment.subList(0, numInputs));

        final long oneFullAssignmentSize = oneFullAssignment.count();
        linearCombinationA.count();
        linearCombinationB.count();
        linearCombinationC.count();

        final R1CSConstraintsRDD<FieldT> constraints = new R1CSConstraintsRDD<>(
                linearCombinationA,
                linearCombinationB,
                linearCombinationC,
                numConstraints);

        final R1CSRelationRDD<FieldT> r1cs = new R1CSRelationRDD<>(
                constraints,
                numInputs,
                numAuxiliary);

        assert (r1cs.numInputs() == numInputs);
        assert (r1cs.numVariables() >= numInputs);
        assert (r1cs.numVariables() == oneFullAssignmentSize);
        assert (r1cs.numConstraints() == numConstraints);
        assert (r1cs.isSatisfied(primary, oneFullAssignment));

        return new Tuple3<>(r1cs, primary, oneFullAssignment);
    }

    public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        R1CSRelationRDD<FieldT>
    distributedR1CSFromText(
            String filePath,
            final Configuration config) {

        String[] constraintParameters = new String[3];
        try{
            constraintParameters = new BufferedReader(
                    new FileReader(filePath + ".problem_size")).readLine().split(" ");

        } catch (Exception e){
            System.err.println("Error: " + e.getMessage());
        }
        int numInputs = Integer.parseInt(constraintParameters[0]);
        int numAuxiliary = Integer.parseInt(constraintParameters[1]);
        int numConstraints = Integer.parseInt(constraintParameters[2]);

        // Need at least one constraint per partition!
        assert(numConstraints >= config.numPartitions());

        final ArrayList<Integer> partitions = constructPartitionArray(config.numPartitions(), numConstraints);

        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationA = distributedCombinationFromStream(
                filePath + ".a", config, partitions, numConstraints);

        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationB = distributedCombinationFromStream(
                filePath + ".b", config, partitions, numConstraints);

        JavaPairRDD<Long, LinearTerm<FieldT>> linearCombinationC = distributedCombinationFromStream(
                filePath + ".c", config, partitions, numConstraints);

        linearCombinationA.count();
        linearCombinationB.count();
        linearCombinationC.count();

        final R1CSConstraintsRDD<FieldT> constraints = new R1CSConstraintsRDD<>(
                linearCombinationA,
                linearCombinationB,
                linearCombinationC,
                numConstraints);

        return new R1CSRelationRDD<>(constraints, numInputs, numAuxiliary);
    }

    private static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        LinearCombination<FieldT>
    JSONObjectToCombination (final JSONObject matrixRow) {

        final LinearCombination<FieldT> L = new LinearCombination<>();

        Iterator<String> keys = matrixRow.keySet().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            BN254aFields.BN254aFr value;
            try{
                value = new BN254aFields.BN254aFr((String) matrixRow.get(key));
            } catch (ClassCastException e){
                // Handle case when key-value pairs are String: Long
                value = new BN254aFields.BN254aFr(Long.toString((long) matrixRow.get(key)));
            }
            L.add(new LinearTerm<>(Long.parseLong(key), (FieldT) value));
        }
        return L;
    }

    private static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        LinearCombination<FieldT>
    makeRowAt (long index, BufferedReader reader) {
        // Assumes input to be ordered by row and that the last line is blank.
        final LinearCombination<FieldT> L = new LinearCombination<>();

        try {
            String nextLine;
            while ((nextLine = reader.readLine()) != null) {
                String[] tokens = nextLine.split(" ");

                int col = Integer.parseInt(tokens[0]);
                int row = Integer.parseInt(tokens[1]);
                assert(row >= index);

                if (index == row) {
                    reader.mark(100);
                    L.add(new LinearTerm<>(col, (FieldT) new BN254aFields.BN254aFr(tokens[2])));
                } else if (row > index) {
                    reader.reset();
                    return L;
                }
            }
        } catch (Exception e){
            System.err.println("Error: " + e.getMessage());
        }
        return L;
    }

    private static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        JavaPairRDD<Long, LinearTerm<FieldT>>
    distributedCombinationFromJSON(
            Configuration config,
            ArrayList<Integer> partitions,
            JSONArray[] constraintArray,
            int constraintArrayIndex,
            int numConstraints
    ){
        final int numPartitions = config.numPartitions();

        // Need at least one constraint per partition!
        assert(numConstraints >= numPartitions);

        return config.sparkContext()
                .parallelize(partitions, numPartitions).flatMapToPair(part -> {
            final long partSize = part == numPartitions ?
                    numConstraints % (numConstraints / numPartitions) : numConstraints / numPartitions;

            final ArrayList<Tuple2<Long, LinearTerm<FieldT>>> T = new ArrayList<>();
            for (long i = 0; i < partSize; i++) {
                final long index = part * (numConstraints / numPartitions) + i;

                JSONObject next = (JSONObject) constraintArray[(int) index].get(constraintArrayIndex);
                Iterator<String> keys = next.keySet().iterator();
                while (keys.hasNext()) {
                    String key = keys.next();

                    long columnIndex = Long.parseLong(key);
                    BN254aFields.BN254aFr value;
                    try{
                        value = new BN254aFields.BN254aFr((String) next.get(key));
                    } catch (ClassCastException e){
                        // Handle case when key-value pairs are String: Long
                        value = new BN254aFields.BN254aFr(Long.toString((long) next.get(key)));
                    }
                    T.add(new Tuple2<>(index, new LinearTerm<>(columnIndex, (FieldT) value)));
                }

            }
            return T.iterator();
        });
    }

    private static <FieldT extends AbstractFieldElementExpanded<FieldT>>
        JavaPairRDD<Long, LinearTerm<FieldT>>
    distributedCombinationFromStream(
            String fileName,
            Configuration config,
            ArrayList<Integer> partitions,
            int numConstraints
    ){
        final int numPartitions = config.numPartitions();

        // Need at least one constraint per partition!
        assert(numConstraints >= numPartitions);

        JavaPairRDD<Long, LinearTerm<FieldT>> result;
        try {
            final BufferedReader br = new BufferedReader(new FileReader(fileName));
            // Not gonna work. Buffered reader gets reopened for each partition. May need to partition the file.

            result = config.sparkContext().parallelize(partitions, numPartitions).flatMapToPair(part -> {
                final long partSize = part == numPartitions ? numConstraints %
                        (numConstraints / numPartitions) : numConstraints / numPartitions;

                final ArrayList<Tuple2<Long, LinearTerm<FieldT>>> T = new ArrayList<>();
                for (long i = 0; i < partSize; i++) {

                    final long index = part * (numConstraints / numPartitions) + i;

                    LinearCombination<FieldT> combinationA = makeRowAt(index, br);

                    for (LinearTerm term: combinationA.terms()) {
                        T.add(new Tuple2<>(index, term));
                    }

                }
                return T.iterator();
            });
            br.close();
            return result;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        return null;

    }

    private static ArrayList<Integer> constructPartitionArray(int numPartitions, long numConstraints){
        final ArrayList<Integer> partitions = new ArrayList<>();
        for (int i = 0; i < numPartitions; i++) {
            partitions.add(i);
        }
        if (numConstraints % 2 != 0) {
            partitions.add(numPartitions);
        }
        return partitions;
    }
}
