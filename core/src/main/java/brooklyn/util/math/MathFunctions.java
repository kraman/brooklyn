package brooklyn.util.math;

import javax.annotation.Nullable;

import com.google.common.base.Function;

public class MathFunctions {

    public static Function<Number,Double> divide(final double divisor) {
        return new Function<Number, Double>() {
            public Double apply(@Nullable Number input) {
                if (input==null) return null;
                return input.doubleValue() / divisor;
            }
        };
    }

    public static <T> Function<T, Double> divide(final Function<T, ? extends Number> input, final double divisor) {
        return new Function<T, Double>() {
            public Double apply(@Nullable T input2) {
                Number n = input.apply(input2);
                if (n==null) return null;
                return n.doubleValue() / divisor;
            }
        };
    }
    
}
