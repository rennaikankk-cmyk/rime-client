package dev.guizhou.util.player;

public final class ArmorScoreTest {
    private static int checks;

    private static ArmorScore.Piece piece(double armor, double toughness, int prot, int blast, int left, int max, boolean binding) {
        return new ArmorScore.Piece(armor, toughness, prot, blast, 3, left, max, binding);
    }

    public static void main(String[] args) {
        ArmorScore.Piece netherite = piece(8, 3, 4, 0, 592, 592, false);
        ArmorScore.Piece diamond = piece(8, 2, 4, 0, 528, 528, false);
        ArmorScore.Piece iron = piece(6, 0, 0, 0, 240, 240, false);
        check(ArmorScore.shouldReplace(null, iron, false, 0.1), "Empty slot takes any armor");
        check(ArmorScore.shouldReplace(diamond, netherite, false, 0.1), "Toughness breaks the defense tie");
        check(!ArmorScore.shouldReplace(netherite, diamond, false, 0.1), "Never downgrade a healthy piece");
        check(!ArmorScore.shouldReplace(netherite, piece(8, 3, 4, 0, 590, 592, false), false, 0.1),
                "Near-identical pieces do not churn");

        ArmorScore.Piece blastLegs = piece(6, 3, 0, 4, 555, 555, false);
        ArmorScore.Piece protLegs = piece(6, 3, 4, 0, 555, 555, false);
        check(ArmorScore.shouldReplace(protLegs, blastLegs, true, 0.1), "Blast preference favors blast leggings");
        check(!ArmorScore.shouldReplace(blastLegs, protLegs, true, 0.1), "and keeps them");
        check(ArmorScore.shouldReplace(blastLegs, protLegs, false, 0.1), "Without the preference Protection wins");

        ArmorScore.Piece broken = piece(8, 3, 4, 0, 30, 592, false);
        check(ArmorScore.low(broken, 0.1), "5% durability is low");
        check(ArmorScore.shouldReplace(broken, diamond, false, 0.1), "A breaking piece yields to a healthy spare");
        check(!ArmorScore.shouldReplace(diamond, broken, false, 0.1), "A breaking spare is never equipped");
        check(!ArmorScore.shouldReplace(broken, broken, false, 0.1), "Two breaking pieces do not swap");
        check(!ArmorScore.low(broken, 0.0), "LowDurability 0 disables the check");

        ArmorScore.Piece cursed = piece(8, 3, 4, 0, 592, 592, true);
        check(!ArmorScore.shouldReplace(null, cursed, false, 0.1), "Curse of Binding is never auto-equipped");
        check(!ArmorScore.shouldReplace(cursed, netherite, false, 0.1), "A cursed piece cannot be taken off");

        ArmorScore.Piece unbreakable = piece(2, 0, 0, 0, 0, 0, false);
        check(!ArmorScore.low(unbreakable, 0.5), "Non-damageable items are never low");
        System.out.println("ArmorScoreTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
