
package org.example.subsidy.ai;

public final class InnValidator {

    private static final int[] W10 =
            {2, 4, 10, 3, 5, 9, 4, 6, 8};

    private static final int[] W12A =
            {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    private static final int[] W12B =
            {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    private InnValidator() {
    }

    public static boolean isValid(String inn) {

        if (inn == null ||
                !inn.matches("(?:[0-9]{10}|[0-9]{12})")) {
            return false;
        }

        if (inn.length() == 10) {
            return check(inn, W10) == digit(inn, 9);
        }

        return check(inn, W12A) == digit(inn, 10)
                && check(inn, W12B) == digit(inn, 11);
    }

    private static int check(String inn, int[] weights) {

        int sum = 0;

        for (int i = 0; i < weights.length; i++) {
            sum += digit(inn, i) * weights[i];
        }

        return (sum % 11) % 10;
    }

    private static int digit(String inn, int index) {
        return inn.charAt(index) - '0';
    }
}
