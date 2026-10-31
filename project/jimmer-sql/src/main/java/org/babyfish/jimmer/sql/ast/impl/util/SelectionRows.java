package org.babyfish.jimmer.sql.ast.impl.util;

import org.babyfish.jimmer.sql.ast.tuple.*;
import org.babyfish.jimmer.sql.runtime.TupleCreator;

/**
 * Builds the row value a selection list returns from its column values.
 */
public final class SelectionRows {

    private SelectionRows() {}

    /**
     * Returns {@code tupleCreator.createTuple(args)}, or, without a creator, the single value or the
     * {@code TupleN} that the default reader of {@code args.length} selections produces.
     *
     * @throws IllegalArgumentException if there is no creator and the value count is not between 1 and 9
     */
    public static Object create(TupleCreator<?> tupleCreator, Object[] args) {
        if (tupleCreator != null) {
            return tupleCreator.createTuple(args);
        }
        switch (args.length) {
            case 1:
                return args[0];
            case 2:
                return new Tuple2<>(args[0], args[1]);
            case 3:
                return new Tuple3<>(args[0], args[1], args[2]);
            case 4:
                return new Tuple4<>(args[0], args[1], args[2], args[3]);
            case 5:
                return new Tuple5<>(args[0], args[1], args[2], args[3], args[4]);
            case 6:
                return new Tuple6<>(args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7:
                return new Tuple7<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8:
                return new Tuple8<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 9:
                return new Tuple9<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            default:
                throw new IllegalArgumentException(
                        "The selection count must be between 1 and 9, but there are " + args.length
                );
        }
    }
}
