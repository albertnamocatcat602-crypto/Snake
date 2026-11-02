import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

/**
 * SnakeGame: a single-file Java (16+) game that uses only Swing and the JDK's built-in sound.
 *
 * Highlights
 *  - Eat a different fruit every time. Eat fruits quickly one after another to build a COMBO:
 *    every fruit in the streak scores more and plays the next note of a rising melody.
 *    A rare golden apple is worth 3 points.
 *  - Smooth 60 FPS animation: the game logic ticks on a timer, the drawing slides between ticks.
 *  - The snake is drawn as one flowing body with scales, a gulp that travels down it when it eats,
 *    and a head that looks at the fruit and opens its mouth when it gets close.
 *  - All sound is synthesized in code (no audio files): fruit-specific crunch / squish / pop sounds,
 *    jingles and a looping background tune, mixed together on a single audio line.
 *  - The window size adapts to your screen (laptop friendly) and can be resized or maximized.
 *
 * Data structures and algorithms
 *  - Deque (ArrayDeque): the snake body (add head / remove tail in O(1)) and the queued key presses.
 *  - HashSet: O(1) collision checks against the snake body.
 *  - Queue + HashMap: BFS shortest-path search for the auto-play bot.
 *  - Arc-length resampling and moving-average smoothing turn the grid path into a flowing body.
 *
 * Controls
 *  Arrow keys / WASD  move        SPACE  start / play again       P  pause / resume
 *  B  toggle the BFS bot          M  mute all sound               N  music on / off
 *  C  change snake colour         R  restart
 *
 * Run from VS Code: open this file and press the "Run" button above main(),
 * or from a terminal:  javac SnakeGame.java && java SnakeGame
 */
@SuppressWarnings("this-escape")
public class SnakeGame extends JPanel {

    private static final long serialVersionUID = 1L;

    // ---------------------------------------------------------------- Config
    // All drawing happens in "logical" units (one cell = CELL units) and is scaled to the window size.

    private static final int COLUMNS = 30;
    private static final int ROWS = 20;
    private static final int CELL = 32;
    private static final int HUD_HEIGHT = 64;
    private static final int BOARD_W = COLUMNS * CELL;
    private static final int BOARD_H = ROWS * CELL;
    private static final int VIEW_W = BOARD_W;
    private static final int VIEW_H = BOARD_H + HUD_HEIGHT;

    private static final int START_DELAY_MS = 140;
    private static final int MIN_DELAY_MS = 62;
    private static final int DELAY_STEP_MS = 9;           // faster by this much every level
    private static final int FRUITS_PER_LEVEL = 5;
    private static final int SNAKE_START_LENGTH = 3;
    private static final int FRAME_MS = 16;               // ~60 FPS
    private static final int MAX_QUEUED_TURNS = 2;
    private static final int MAX_PARTICLES = 900;
    private static final double SHAKE_SECONDS = 0.45;
    private static final double COMBO_WINDOW = 3.0;       // seconds to grab the next fruit and keep the streak
    private static final double GOLDEN_CHANCE = 0.10;

    private static final double BODY_RADIUS = CELL * 0.38;
    private static final double BODY_STEP = 3.5;          // spacing of the samples that make up the body
    private static final int SMOOTH_WINDOW = 4;
    private static final double BULGE_SPEED = 260;        // how fast a swallowed fruit travels down the body

    // ---------------------------------------------------------------- Look

    private static final Color GRASS_LIGHT = new Color(166, 214, 84);
    private static final Color GRASS_DARK = new Color(156, 206, 74);
    private static final Color HEDGE_DARK = new Color(40, 92, 48);
    private static final Color HEDGE_LIGHT = new Color(72, 140, 68);
    private static final Color HUD_DARK = new Color(40, 66, 38);
    private static final Color HUD_LIGHT = new Color(78, 118, 56);
    private static final Color GOLD = new Color(255, 214, 80);
    private static final Color TONGUE = new Color(238, 64, 96);
    private static final Color LEAF_LIGHT = new Color(120, 205, 90);
    private static final Color LEAF_DARK = new Color(40, 130, 56);

    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 72);
    private static final Font FONT_BIG = new Font(Font.SANS_SERIF, Font.BOLD, 28);
    private static final Font FONT_MEDIUM = new Font(Font.SANS_SERIF, Font.BOLD, 20);
    private static final Font FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    private static final Font FONT_TAG = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_POP = new Font(Font.SANS_SERIF, Font.BOLD, 26);
    private static final Font FONT_COMBO = new Font(Font.SANS_SERIF, Font.BOLD, 20);
    private static final Font FONT_LEVEL_UP = new Font(Font.SANS_SERIF, Font.BOLD, 48);

    // ----------------------------------------------------------------- Types

    private enum State { READY, RUNNING, PAUSED, GAME_OVER }

    private enum Direction {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

        final int dx;
        final int dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        Direction opposite() {
            return switch (this) {
                case UP -> DOWN;
                case DOWN -> UP;
                case LEFT -> RIGHT;
                case RIGHT -> LEFT;
            };
        }
    }

    /** How a fruit sounds when it is eaten. */
    private enum Flavor { CRUNCH, JUICY, POP, SOFT }

    private enum Fruit {
        APPLE(1, Flavor.CRUNCH, new Color(238, 64, 64), new Color(255, 190, 120)),
        ORANGE(1, Flavor.JUICY, new Color(255, 152, 32), new Color(255, 214, 120)),
        CHERRY(1, Flavor.POP, new Color(206, 28, 62), new Color(255, 130, 150)),
        STRAWBERRY(1, Flavor.JUICY, new Color(240, 60, 92), new Color(255, 170, 190)),
        GRAPES(1, Flavor.POP, new Color(150, 84, 206), new Color(214, 170, 255)),
        BANANA(1, Flavor.SOFT, new Color(255, 224, 78), new Color(255, 244, 170)),
        GOLDEN(3, Flavor.CRUNCH, new Color(255, 204, 40), new Color(255, 248, 190));

        final int points;
        final Flavor flavor;
        final Color juice;     // colour of the splash when it is eaten
        final Color accent;

        Fruit(int points, Flavor flavor, Color juice, Color accent) {
            this.points = points;
            this.flavor = flavor;
            this.juice = juice;
            this.accent = accent;
        }
    }

    private static final Fruit[] REGULAR_FRUITS = {
        Fruit.APPLE, Fruit.ORANGE, Fruit.CHERRY, Fruit.STRAWBERRY, Fruit.GRAPES, Fruit.BANANA};

    private enum Skin {
        OCEAN(new Color(150, 214, 255), new Color(70, 136, 250), new Color(36, 78, 202),
                new Color(22, 50, 150), new Color(206, 242, 255)),
        JUNGLE(new Color(170, 244, 140), new Color(70, 192, 98), new Color(32, 130, 66),
                new Color(18, 82, 44), new Color(228, 252, 184)),
        GRAPE(new Color(222, 180, 255), new Color(156, 104, 232), new Color(98, 58, 184),
                new Color(62, 34, 122), new Color(244, 222, 255)),
        SUNSET(new Color(255, 208, 130), new Color(252, 144, 62), new Color(212, 84, 42),
                new Color(142, 46, 30), new Color(255, 238, 196));

        final Color head;
        final Color main;
        final Color dark;
        final Color outline;
        final Color belly;

        Skin(Color head, Color main, Color dark, Color outline, Color belly) {
            this.head = head;
            this.main = main;
            this.dark = dark;
            this.outline = outline;
            this.belly = belly;
        }
    }

    /** A position on the grid. Records give us equals/hashCode for free, so it works in a HashSet. */
    private record Cell(int x, int y) {
        Cell move(Direction d) {
            return new Cell(x + d.dx, y + d.dy);
        }

        boolean isInsideGrid() {
            return x >= 0 && x < COLUMNS && y >= 0 && y < ROWS;
        }
    }

    /** The snake body as many closely spaced points, from the head to the tail. */
    private record BodyPath(double[] x, double[] y, int count, double step, double length) { }

    /** A tiny dot, droplet or sparkle that flies outwards and fades away. */
    private static final class Particle {
        static final int DOT = 0;
        static final int STAR = 1;

        double x;
        double y;
        double vx;
        double vy;
        final double gravity;
        double life;
        final double maxLife;
        final double size;
        final Color color;
        final int kind;

        Particle(double x, double y, double vx, double vy, double gravity, double life, double size,
                 Color color, int kind) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.gravity = gravity;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.color = color;
            this.kind = kind;
        }
    }

    /** An expanding circle that fades out. */
    private static final class Ring {
        final double x;
        final double y;
        final double maxRadius;
        final Color color;
        double life;
        final double maxLife;

        Ring(double x, double y, double maxRadius, Color color, double life) {
            this.x = x;
            this.y = y;
            this.maxRadius = maxRadius;
            this.color = color;
            this.life = life;
            this.maxLife = life;
        }
    }

    /** Text that floats upwards and fades out ("+2", "LEVEL UP!"). */
    private static final class FloatText {
        final String text;
        final double x;
        double y;
        double life;
        final double maxLife;
        final Font font;
        final Color color;

        FloatText(String text, double x, double y, Font font, Color color, double life) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.font = font;
            this.color = color;
            this.life = life;
            this.maxLife = life;
        }
    }

    private enum Sfx {
        TURN(60), START(0), PAUSE(0), RESUME(0), LEVEL_UP(0), GOLD(0), COMBO_END(0), CRASH(0), WIN(0),
        BOT_ON(0), BOT_OFF(0), CLICK(0);

        final int minGapMs;   // stops the same sound from firing too often

        Sfx(int minGapMs) {
            this.minGapMs = minGapMs;
        }
    }

    // ----------------------------------------------------------------- State

    private final Deque<Cell> snake = new ArrayDeque<>();            // first = head, last = tail
    private final Set<Cell> occupied = new HashSet<>();              // same cells as the deque, for fast lookup
    private final Deque<Direction> turnQueue = new ArrayDeque<>();   // buffered key presses
    private final List<Particle> particles = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<FloatText> floatTexts = new ArrayList<>();
    private final List<double[]> bulges = new ArrayList<>();         // {distance from head}: swallowed fruits
    private final List<double[]> decorations = new ArrayList<>();    // x, y, kind, variant
    private final double[][] butterflies = new double[3][5];         // baseX, baseY, speed, phase, colour
    private final double[][] clouds = new double[5][5];              // x, y, radius x, radius y, speed
    private final Random random = new Random();
    private final Sounds sounds = new Sounds();
    private final Timer gameTimer;    // game logic
    private final Timer frameTimer;   // animation / repainting

    private Direction direction;
    private Cell food;
    private Fruit foodType = Fruit.APPLE;
    private Cell prevTail;            // tail cell removed by the last step (null if the snake just grew)
    private State state = State.READY;
    private Skin skin = Skin.OCEAN;
    private boolean autoPlay;
    private boolean won;
    private boolean newBest;
    private int score;
    private int highScore;
    private int level;
    private int fruitsEaten;
    private int combo;
    private double comboTimeLeft;

    private long lastTickNanos;
    private long lastFrameNanos;
    private double frozenProgress;    // step progress saved while paused
    private double animTime;          // seconds, drives all looping animations
    private double wiggleTime;        // advances only while the snake is moving
    private double foodSpawnTime;
    private double shakeTime;
    private double flash;
    private double scorePop;          // briefly enlarges the score after eating
    private double mouth;             // 0 = closed, 1 = wide open
    private double lastBodyLength;

    // ----------------------------------------------------------- Constructor

    public SnakeGame() {
        setPreferredSize(preferredWindowSize());
        setBackground(HUD_DARK.darker());
        setFocusable(true);

        createScenery();

        gameTimer = new Timer(START_DELAY_MS, e -> tick());
        frameTimer = new Timer(FRAME_MS, e -> onFrame());
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e.getKeyCode());
            }
        });

        resetGame();
        lastFrameNanos = System.nanoTime();
        frameTimer.start();
    }

    /** Picks a window size that fits the screen, so the whole game is visible on a laptop. */
    private static Dimension preferredWindowSize() {
        double scale = 0.9;
        try {
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            double byWidth = usable.width * 0.92 / VIEW_W;
            double byHeight = (usable.height - 70) * 0.92 / VIEW_H;   // leave room for the title bar
            scale = Math.min(byWidth, byHeight);
        } catch (HeadlessException e) {
            // no screen available: keep the default scale
        }
        scale = Math.max(0.5, Math.min(scale, 1.5));
        return new Dimension((int) (VIEW_W * scale), (int) (VIEW_H * scale));
    }

    /** Flowers, grass tufts, butterflies and drifting cloud shadows: they never affect the game itself. */
    private void createScenery() {
        Random scenery = new Random(77);
        for (int x = 0; x < COLUMNS; x++) {
            for (int y = 0; y < ROWS; y++) {
                double roll = scenery.nextDouble();
                if (roll < 0.40) {
                    int kind = roll < 0.05 ? 1 : roll < 0.09 ? 2 : 0;   // 0 tuft, 1 flower, 2 clover
                    decorations.add(new double[] {
                        x * CELL + 5 + scenery.nextDouble() * (CELL - 10),
                        y * CELL + 5 + scenery.nextDouble() * (CELL - 10), kind, scenery.nextInt(4)});
                }
            }
        }
        for (double[] b : butterflies) {
            b[0] = 220 + scenery.nextDouble() * (BOARD_W - 440);
            b[1] = 150 + scenery.nextDouble() * (BOARD_H - 300);
            b[2] = 0.35 + scenery.nextDouble() * 0.3;
            b[3] = scenery.nextDouble() * Math.PI * 2;
            b[4] = scenery.nextInt(3);
        }
        for (double[] c : clouds) {
            c[0] = scenery.nextDouble() * BOARD_W;
            c[1] = scenery.nextDouble() * BOARD_H;
            c[2] = 90 + scenery.nextDouble() * 90;
            c[3] = 40 + scenery.nextDouble() * 40;
            c[4] = 8 + scenery.nextDouble() * 10;
        }
    }

    // ------------------------------------------------------------- Game flow

    private int currentDelay() {
        return Math.max(MIN_DELAY_MS, START_DELAY_MS - (level - 1) * DELAY_STEP_MS);
    }

    private void resetGame() {
        gameTimer.stop();
        snake.clear();
        occupied.clear();
        turnQueue.clear();
        particles.clear();
        rings.clear();
        floatTexts.clear();
        bulges.clear();
        prevTail = null;
        direction = Direction.RIGHT;
        score = 0;
        level = 1;
        fruitsEaten = 0;
        combo = 0;
        comboTimeLeft = 0;
        won = false;
        newBest = false;
        shakeTime = 0;
        flash = 0;
        mouth = 0;
        gameTimer.setDelay(currentDelay());

        int startX = COLUMNS / 3;
        int startY = ROWS - 7;
        for (int i = 0; i < SNAKE_START_LENGTH; i++) {
            Cell part = new Cell(startX - i, startY);
            snake.addLast(part);
            occupied.add(part);
        }

        foodType = Fruit.APPLE;
        spawnFood();
        state = State.READY;
    }

    private void startGame() {
        state = State.RUNNING;
        gameTimer.setInitialDelay(gameTimer.getDelay());
        // Pretend the last step just finished so the snake holds still until the first real tick.
        lastTickNanos = System.nanoTime() - gameTimer.getDelay() * 1_000_000L;
        gameTimer.start();
        sounds.play(Sfx.START);
    }

    private void togglePause() {
        if (state == State.RUNNING) {
            frozenProgress = stepProgress();
            state = State.PAUSED;
            gameTimer.stop();
            sounds.play(Sfx.PAUSE);
        } else if (state == State.PAUSED) {
            int delay = gameTimer.getDelay();
            gameTimer.setInitialDelay(Math.max(1, (int) ((1 - frozenProgress) * delay)));
            lastTickNanos = System.nanoTime() - (long) (frozenProgress * delay * 1_000_000.0);
            state = State.RUNNING;
            gameTimer.start();
            sounds.play(Sfx.RESUME);
        }
    }

    /** One game step: called by the game timer. */
    private void tick() {
        if (autoPlay) {
            direction = chooseAutoDirection();
            turnQueue.clear();
        } else if (!turnQueue.isEmpty()) {
            direction = turnQueue.poll();
        }

        Cell next = snake.peekFirst().move(direction);
        boolean eating = next.equals(food);

        if (!isSafe(next, eating)) {
            crash();
            return;
        }

        prevTail = null;
        if (!eating) {
            prevTail = snake.removeLast();
            occupied.remove(prevTail);
        }
        snake.addFirst(next);
        occupied.add(next);
        lastTickNanos = System.nanoTime();

        if (eating) {
            onFruitEaten(next);
        }
    }

    private void onFruitEaten(Cell eatenAt) {
        double x = center(eatenAt.x());
        double y = center(eatenAt.y());
        Fruit fruit = foodType;

        combo = comboTimeLeft > 0 ? combo + 1 : 1;
        comboTimeLeft = COMBO_WINDOW;
        int points = fruit.points + Math.min(combo - 1, 4);

        score += points;
        fruitsEaten++;
        scorePop = 1;
        if (score > highScore) {
            highScore = score;
            newBest = true;
        }
        bulges.add(new double[] {0});

        splash(x, y, fruit);
        floatTexts.add(new FloatText("+" + points, x, y - 14, FONT_POP, fruit == Fruit.GOLDEN ? GOLD : Color.WHITE, 1.0));
        if (combo >= 2) {
            floatTexts.add(new FloatText("COMBO x" + combo, x, y - 42, FONT_COMBO,
                    new Color(255, 170, 60), 1.0));
        }

        boolean levelUp = fruitsEaten % FRUITS_PER_LEVEL == 0;
        if (levelUp) {
            level = 1 + fruitsEaten / FRUITS_PER_LEVEL;
            gameTimer.setDelay(currentDelay());
            floatTexts.add(new FloatText("LEVEL " + level + "!", BOARD_W / 2.0, BOARD_H / 2.0 - 40,
                    FONT_LEVEL_UP, GOLD, 1.7));
            confetti(BOARD_W / 2.0, BOARD_H / 2.0, 70);
        }

        if (!spawnFood()) {   // the board is full: the player wins
            win();
            return;
        }
        if (fruit == Fruit.GOLDEN) {
            sounds.play(Sfx.GOLD);
        } else {
            sounds.playEat(fruit.flavor, combo);
        }
        if (levelUp) {
            sounds.play(Sfx.LEVEL_UP);
        }
    }

    private void crash() {
        state = State.GAME_OVER;
        gameTimer.stop();
        shakeTime = SHAKE_SECONDS;
        flash = 1;
        combo = 0;
        comboTimeLeft = 0;
        Cell head = snake.peekFirst();
        double x = center(head.x());
        double y = center(head.y());
        Skin s = skin;
        burst(x, y, 40, new Color[] {s.main, s.head, Color.WHITE, new Color(255, 110, 90)}, 80, 340, 160, Particle.DOT);
        rings.add(new Ring(x, y, 70, new Color(255, 255, 255), 0.45));
        sounds.play(Sfx.CRASH);
    }

    private void win() {
        won = true;
        state = State.GAME_OVER;
        gameTimer.stop();
        confetti(BOARD_W / 2.0, BOARD_H / 2.0, 160);
        sounds.play(Sfx.WIN);
    }

    /**
     * A cell is safe if it is on the board and not part of the snake.
     * The one exception is the tail cell, which moves out of the way unless the snake is growing.
     */
    private boolean isSafe(Cell cell, boolean growing) {
        if (!cell.isInsideGrid()) {
            return false;
        }
        if (!occupied.contains(cell)) {
            return true;
        }
        return !growing && cell.equals(snake.peekLast());
    }

    /** Places a fruit on a random free cell. Returns false if there is no free cell left. */
    private boolean spawnFood() {
        List<Cell> freeCells = new ArrayList<>();
        for (int x = 0; x < COLUMNS; x++) {
            for (int y = 0; y < ROWS; y++) {
                Cell cell = new Cell(x, y);
                if (!occupied.contains(cell)) {
                    freeCells.add(cell);
                }
            }
        }
        if (freeCells.isEmpty()) {
            food = null;
            return false;
        }
        food = freeCells.get(random.nextInt(freeCells.size()));
        foodType = pickFruit();
        foodSpawnTime = animTime;
        return true;
    }

    private Fruit pickFruit() {
        if (fruitsEaten >= 3 && foodType != Fruit.GOLDEN && random.nextDouble() < GOLDEN_CHANCE) {
            return Fruit.GOLDEN;
        }
        Fruit pick;
        do {
            pick = REGULAR_FRUITS[random.nextInt(REGULAR_FRUITS.length)];
        } while (pick == foodType);   // never the same fruit twice in a row
        return pick;
    }

    // -------------------------------------------------------------- Auto-play

    /** Bot logic: follow the shortest path to the fruit, otherwise just survive. */
    private Direction chooseAutoDirection() {
        Direction toFood = firstStepToFood();
        if (toFood != null) {
            return toFood;
        }
        Cell head = snake.peekFirst();
        for (Direction d : Direction.values()) {
            if (d != direction.opposite() && isSafe(head.move(d), false)) {
                return d;
            }
        }
        return direction;
    }

    /**
     * Breadth-first search from the head to the fruit, treating the snake body as walls.
     * For every visited cell we remember which first move leads to it, so no path rebuild is needed.
     *
     * @return the first move of the shortest path, or null if the fruit is unreachable
     */
    private Direction firstStepToFood() {
        Cell start = snake.peekFirst();
        Map<Cell, Direction> firstStep = new HashMap<>();
        Queue<Cell> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            Cell current = queue.poll();
            for (Direction d : Direction.values()) {
                if (current.equals(start) && d == direction.opposite()) {
                    continue;   // the snake cannot reverse into itself
                }
                Cell neighbor = current.move(d);
                if (!neighbor.isInsideGrid() || occupied.contains(neighbor)
                        || firstStep.containsKey(neighbor)) {
                    continue;
                }
                Direction step = current.equals(start) ? d : firstStep.get(current);
                if (neighbor.equals(food)) {
                    return step;
                }
                firstStep.put(neighbor, step);
                queue.add(neighbor);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Input

    private void handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> requestTurn(Direction.UP);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> requestTurn(Direction.DOWN);
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> requestTurn(Direction.LEFT);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> requestTurn(Direction.RIGHT);
            case KeyEvent.VK_SPACE -> {
                if (state == State.GAME_OVER) {
                    resetGame();
                }
                if (state == State.READY) {
                    startGame();
                }
            }
            case KeyEvent.VK_P -> togglePause();
            case KeyEvent.VK_R -> resetGame();
            case KeyEvent.VK_B -> {
                autoPlay = !autoPlay;
                sounds.play(autoPlay ? Sfx.BOT_ON : Sfx.BOT_OFF);
            }
            case KeyEvent.VK_M -> {
                sounds.toggleMute();
                sounds.play(Sfx.CLICK);
            }
            case KeyEvent.VK_N -> {
                sounds.toggleMusic();
                sounds.play(Sfx.CLICK);
            }
            case KeyEvent.VK_C -> {
                Skin[] all = Skin.values();
                skin = all[(skin.ordinal() + 1) % all.length];
                sounds.play(Sfx.CLICK);
            }
            default -> { }
        }
    }

    /** Buffers up to two turns, so quick key combos (up then left) are not lost between ticks. */
    private void requestTurn(Direction requested) {
        if (state != State.READY && state != State.RUNNING) {
            return;
        }
        Direction last = turnQueue.isEmpty() ? direction : turnQueue.peekLast();
        if (requested == last.opposite()) {
            return;
        }
        if (state == State.READY) {
            startGame();
        }
        if (requested != last && turnQueue.size() < MAX_QUEUED_TURNS) {
            turnQueue.addLast(requested);
            sounds.play(Sfx.TURN);
        }
    }

    // ------------------------------------------------------------ Animation

    private void onFrame() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;
        animTime += dt;
        updateCombo(dt);
        updateEffects(dt);
        sounds.setMusicQuiet(state != State.RUNNING);
        repaint();
    }

    private void updateCombo(double dt) {
        if (state != State.RUNNING || comboTimeLeft <= 0) {
            return;
        }
        comboTimeLeft -= dt;
        if (comboTimeLeft <= 0) {
            if (combo >= 3) {
                sounds.play(Sfx.COMBO_END);
            }
            combo = 0;
        }
    }

    private void updateEffects(double dt) {
        if (state == State.RUNNING) {
            wiggleTime += dt;
            for (Iterator<double[]> it = bulges.iterator(); it.hasNext(); ) {
                double[] bulge = it.next();
                bulge[0] += BULGE_SPEED * dt;
                if (bulge[0] > lastBodyLength + 30) {
                    it.remove();
                }
            }
        }

        // The mouth opens as the snake gets close to a fruit.
        double target = 0;
        if (state == State.RUNNING && food != null) {
            Cell head = snake.peekFirst();
            double distance = Math.hypot(center(food.x()) - center(head.x()), center(food.y()) - center(head.y()));
            target = clamp01((CELL * 3.4 - distance) / (CELL * 1.6));
        }
        mouth += (target - mouth) * Math.min(1, dt * 14);

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            double drag = Math.max(0, 1 - 2.4 * dt);
            p.vx *= drag;
            p.vy = p.vy * drag + p.gravity * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        for (Iterator<Ring> it = rings.iterator(); it.hasNext(); ) {
            Ring r = it.next();
            r.life -= dt;
            if (r.life <= 0) {
                it.remove();
            }
        }
        for (Iterator<FloatText> it = floatTexts.iterator(); it.hasNext(); ) {
            FloatText f = it.next();
            f.life -= dt;
            if (f.life <= 0) {
                it.remove();
                continue;
            }
            f.y -= 42 * dt;
        }
        shakeTime = Math.max(0, shakeTime - dt);
        flash = Math.max(0, flash - dt * 2.5);
        scorePop = Math.max(0, scorePop - dt * 4);
    }

    private void burst(double x, double y, int count, Color[] palette, double minSpeed, double maxSpeed,
                       double gravity, int kind) {
        for (int i = 0; i < count && particles.size() < MAX_PARTICLES; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = minSpeed + random.nextDouble() * (maxSpeed - minSpeed);
            double life = 0.5 + random.nextDouble() * 0.6;
            double size = (kind == Particle.STAR ? 6 : 3.5) + random.nextDouble() * 4;
            particles.add(new Particle(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed - 30, gravity,
                    life, size, palette[random.nextInt(palette.length)], kind));
        }
    }

    /** Juice splash when a fruit is eaten. */
    private void splash(double x, double y, Fruit fruit) {
        Color[] palette = {fruit.juice, fruit.accent, Color.WHITE};
        burst(x, y, 18, palette, 60, 250, 260, Particle.DOT);
        burst(x, y, fruit == Fruit.GOLDEN ? 16 : 5, new Color[] {Color.WHITE, GOLD, fruit.accent}, 40, 200, 20,
                Particle.STAR);
        rings.add(new Ring(x, y, 46, fruit.accent, 0.4));
    }

    private void confetti(double x, double y, int count) {
        Color[] palette = {GOLD, Color.WHITE, new Color(255, 110, 190), new Color(110, 225, 255),
            new Color(140, 240, 130), new Color(255, 150, 60)};
        burst(x, y, count, palette, 120, 560, 220, Particle.STAR);
    }

    /** How far the snake has travelled between its previous and current cell (0.0 to 1.0). */
    private double stepProgress() {
        return switch (state) {
            case RUNNING -> {
                double elapsedMs = (System.nanoTime() - lastTickNanos) / 1_000_000.0;
                yield clamp01(elapsedMs / gameTimer.getDelay());
            }
            case PAUSED -> frozenProgress;
            default -> 1.0;
        };
    }

    // --------------------------------------------------------------- Rendering

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Scale the whole scene to fit the window and centre it.
        double scale = Math.min(getWidth() / (double) VIEW_W, getHeight() / (double) VIEW_H);
        g2.translate((getWidth() - VIEW_W * scale) / 2.0, (getHeight() - VIEW_H * scale) / 2.0);
        g2.scale(scale, scale);
        g2.clipRect(0, 0, VIEW_W, VIEW_H);

        drawHud(g2);

        Graphics2D board = (Graphics2D) g2.create();
        board.translate(0, HUD_HEIGHT);
        board.clipRect(0, 0, BOARD_W, BOARD_H);
        if (shakeTime > 0) {
            double magnitude = 9 * shakeTime / SHAKE_SECONDS;
            board.translate(Math.sin(animTime * 90) * magnitude, Math.cos(animTime * 77) * magnitude);
        }
        drawTerrain(board);
        drawFood(board);
        drawSnake(board);
        drawEffects(board);
        drawButterflies(board);
        drawFloatTexts(board);
        if (flash > 0) {
            board.setColor(new Color(255, 40, 40, (int) (flash * 90)));
            board.fillRect(-20, -20, BOARD_W + 40, BOARD_H + 40);
        }
        board.dispose();

        drawOverlay(g2);
        g2.dispose();
    }

    // ---- terrain

    private void drawTerrain(Graphics2D g) {
        // One base fill, then only the light tiles on top: no hairline seams when the scene is scaled.
        g.setColor(GRASS_DARK);
        g.fillRect(0, 0, BOARD_W, BOARD_H);
        g.setColor(GRASS_LIGHT);
        for (int x = 0; x < COLUMNS; x++) {
            for (int y = 0; y < ROWS; y++) {
                if (((x + y) & 1) == 0) {
                    g.fillRect(x * CELL, y * CELL, CELL, CELL);
                }
            }
        }
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double[] d : decorations) {
            drawDecoration(g, d);
        }

        // Soft cloud shadows drift across the grass.
        for (double[] c : clouds) {
            double span = BOARD_W + c[2] * 4;
            double cx = (c[0] + animTime * c[4]) % span - c[2] * 2;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, c[1]), (float) c[2],
                    new float[] {0f, 1f}, new Color[] {new Color(0, 40, 20, 34), new Color(0, 40, 20, 0)}));
            g.fill(new Ellipse2D.Double(cx - c[2], c[1] - c[3], c[2] * 2, c[3] * 2));
        }

        g.setPaint(new RadialGradientPaint(BOARD_W / 2f, BOARD_H / 2f, BOARD_W * 0.62f,
                new float[] {0.55f, 1f}, new Color[] {new Color(0, 0, 0, 0), new Color(0, 40, 0, 90)}));
        g.fillRect(-20, -20, BOARD_W + 40, BOARD_H + 40);

        // Hedge around the garden.
        g.setStroke(new BasicStroke(12f));
        g.setColor(HEDGE_DARK);
        g.draw(new RoundRectangle2D.Double(0, 0, BOARD_W, BOARD_H, 18, 18));
        g.setStroke(new BasicStroke(3f));
        g.setColor(HEDGE_LIGHT);
        g.draw(new RoundRectangle2D.Double(7, 7, BOARD_W - 14, BOARD_H - 14, 10, 10));
    }

    private void drawDecoration(Graphics2D g, double[] d) {
        double x = d[0];
        double y = d[1];
        switch ((int) d[2]) {
            case 1 -> {   // flower
                Color[] petals = {new Color(255, 248, 200), new Color(255, 160, 190), new Color(190, 215, 255),
                    new Color(255, 205, 120)};
                g.setColor(petals[(int) d[3]]);
                for (int i = 0; i < 5; i++) {
                    double a = i * Math.PI * 2 / 5 + d[3];
                    g.fill(new Ellipse2D.Double(x + Math.cos(a) * 2.6 - 1.8, y + Math.sin(a) * 2.6 - 1.8, 3.6, 3.6));
                }
                g.setColor(new Color(255, 196, 50));
                g.fill(new Ellipse2D.Double(x - 1.5, y - 1.5, 3, 3));
            }
            case 2 -> {   // clover
                g.setColor(new Color(92, 160, 78, 190));
                for (int i = 0; i < 3; i++) {
                    double a = -Math.PI / 2 + i * Math.PI * 2 / 3;
                    g.fill(new Ellipse2D.Double(x + Math.cos(a) * 2.6 - 2.2, y + Math.sin(a) * 2.6 - 2.2, 4.4, 4.4));
                }
            }
            default -> {   // grass tuft that sways in the breeze
                double sway = Math.sin(animTime * 1.6 + x * 0.05 + y * 0.03) * 1.4;
                g.setColor(new Color(84, 146, 70, 170));
                g.draw(new Line2D.Double(x - 2, y + 2, x - 3 + sway, y - 3));
                g.draw(new Line2D.Double(x, y + 2, x + sway, y - 4));
                g.draw(new Line2D.Double(x + 2, y + 2, x + 3 + sway, y - 3));
            }
        }
    }

    private void drawButterflies(Graphics2D g) {
        Color[][] palettes = {
            {new Color(255, 150, 190), new Color(255, 215, 235)},
            {new Color(255, 200, 80), new Color(255, 240, 170)},
            {new Color(150, 190, 255), new Color(215, 232, 255)}};
        for (double[] b : butterflies) {
            double t = animTime * b[2] + b[3];
            double x = b[0] + Math.sin(t * 0.9) * 170 + Math.sin(t * 2.3) * 26;
            double y = b[1] + Math.cos(t * 0.7) * 90 + Math.sin(t * 3.1) * 14;
            double vx = Math.cos(t * 0.9) * 0.9 * 170 + Math.cos(t * 2.3) * 2.3 * 26;
            double vy = -Math.sin(t * 0.7) * 0.7 * 90 + Math.cos(t * 3.1) * 3.1 * 14;
            double flap = Math.abs(Math.sin(animTime * 13 + b[3]));
            Color[] palette = palettes[(int) b[4]];

            Graphics2D w = (Graphics2D) g.create();
            w.translate(x, y);
            w.rotate(Math.atan2(vy, vx));
            for (int side = -1; side <= 1; side += 2) {
                w.setColor(withAlpha(palette[0], 235));
                w.fill(new Ellipse2D.Double(-6, side * (1.5 + 4.5 * flap) - 3.6 * (0.4 + flap), 9, 7.2 * (0.4 + flap)));
                w.setColor(withAlpha(palette[1], 235));
                w.fill(new Ellipse2D.Double(-3, side * (1.5 + 3 * flap) - 2 * (0.4 + flap), 5, 4 * (0.4 + flap)));
            }
            w.setColor(new Color(60, 40, 50));
            w.fill(new Ellipse2D.Double(-5, -1.2, 10, 2.4));
            w.dispose();
        }
    }

    // ---- fruit

    private void drawFood(Graphics2D g) {
        if (food == null) {
            return;
        }
        double cx = center(food.x());
        double cy = center(food.y());
        double grow = easeOutBack(clamp01((animTime - foodSpawnTime) / 0.4));
        double scale = Math.max(0.05, grow * (1 + 0.05 * Math.sin(animTime * 6)));
        double by = cy - 1 + Math.sin(animTime * 4) * 1.8;
        Fruit fruit = foodType;

        float glowRadius = (float) (CELL * (fruit == Fruit.GOLDEN ? 1.5 : 1.0));
        int glowAlpha = (int) ((fruit == Fruit.GOLDEN ? 150 : 95) * clamp01(grow));
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, glowRadius, new float[] {0f, 1f},
                new Color[] {withAlpha(fruit.juice, glowAlpha), withAlpha(fruit.juice, 0)}));
        g.fill(new Ellipse2D.Double(cx - glowRadius, cy - glowRadius, glowRadius * 2, glowRadius * 2));

        g.setColor(new Color(0, 0, 0, 52));
        g.fill(new Ellipse2D.Double(cx - 9 * scale, cy + 9 * scale, 18 * scale, 6 * scale));

        drawFruit(g, fruit, cx, by, scale, animTime);

        int sparks = fruit == Fruit.GOLDEN ? 5 : 2;
        for (int i = 0; i < sparks; i++) {
            double a = animTime * 1.7 + i * Math.PI * 2 / sparks;
            double radius = 20 + 4 * Math.sin(animTime * 3 + i);
            double twinkle = 0.5 + 0.5 * Math.sin(animTime * 6 + i * 1.7);
            sparkle(g, cx + Math.cos(a) * radius, by + Math.sin(a) * radius * 0.7, 2 + 3 * twinkle,
                    (int) ((100 + 155 * twinkle) * clamp01(grow)));
        }
    }

    /** Draws one fruit around (cx, cy). A fruit is about 24 units across at scale 1. */
    private void drawFruit(Graphics2D g, Fruit fruit, double cx, double cy, double scale, double time) {
        Graphics2D f = (Graphics2D) g.create();
        f.translate(cx, cy);
        f.rotate(Math.sin(time * 2.2) * 0.07);
        f.scale(scale, scale);
        f.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (fruit) {
            case APPLE -> drawApple(f, false);
            case GOLDEN -> drawApple(f, true);
            case ORANGE -> drawOrange(f);
            case CHERRY -> drawCherries(f, time);
            case STRAWBERRY -> drawStrawberry(f);
            case GRAPES -> drawGrapes(f);
            case BANANA -> drawBanana(f);
        }
        f.dispose();
    }

    private void drawApple(Graphics2D g, boolean golden) {
        Path2D body = new Path2D.Double();
        body.moveTo(0, -8);
        body.curveTo(4, -13, 13, -11, 12, -1);
        body.curveTo(11.5, 8, 6, 13.5, 0, 11.5);
        body.curveTo(-6, 13.5, -11.5, 8, -12, -1);
        body.curveTo(-13, -11, -4, -13, 0, -8);
        body.closePath();
        Color light = golden ? new Color(255, 236, 110) : new Color(255, 126, 112);
        Color dark = golden ? new Color(214, 120, 0) : new Color(184, 18, 40);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-4, -4), 21f, new float[] {0f, 1f},
                new Color[] {light, dark}));
        g.fill(body);
        g.setColor(withAlpha(dark.darker(), 160));
        g.draw(body);
        g.setColor(new Color(255, 255, 255, 170));
        g.fill(new Ellipse2D.Double(-8, -8.5, 6, 4));

        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(104, 68, 30));
        Path2D stem = new Path2D.Double();
        stem.moveTo(0, -8);
        stem.quadTo(1, -12, 3.5, -14.5);
        g.draw(stem);
        drawLeaf(g, 2, -11, -0.55, 1.0);
    }

    private void drawOrange(Graphics2D g) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-4, -4), 21f, new float[] {0f, 1f},
                new Color[] {new Color(255, 214, 110), new Color(224, 100, 8)}));
        g.fill(new Ellipse2D.Double(-11.5, -11, 23, 22.5));
        g.setColor(new Color(190, 80, 0, 150));
        g.draw(new Ellipse2D.Double(-11.5, -11, 23, 22.5));
        g.setColor(new Color(200, 90, 10, 110));
        for (int i = 0; i < 12; i++) {
            double a = i * 2.4;
            double r = 3 + (i * 7 % 6);
            g.fill(new Ellipse2D.Double(Math.cos(a) * r - 0.7, Math.sin(a) * r - 0.7 + 1, 1.4, 1.4));
        }
        g.setColor(new Color(255, 255, 255, 150));
        g.fill(new Ellipse2D.Double(-8, -8, 6, 4));
        g.setColor(new Color(70, 150, 60));
        g.fill(starShape(0, -10.5, 4.4, 1.6, 5, -Math.PI / 2));
        drawLeaf(g, 1.5, -10.5, -0.3, 1.0);
    }

    private void drawCherries(Graphics2D g, double time) {
        double[][] balls = {{-6.2, 6.2}, {6.4, 7.6}};
        g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(92, 118, 40));
        for (double[] b : balls) {
            Path2D stem = new Path2D.Double();
            stem.moveTo(b[0], b[1] - 6);
            stem.quadTo(b[0] * 0.4, -9, 0.5, -13);
            g.draw(stem);
        }
        for (double[] b : balls) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(b[0] - 2, b[1] - 2.4), 11f, new float[] {0f, 1f},
                    new Color[] {new Color(255, 108, 128), new Color(150, 8, 40)}));
            g.fill(new Ellipse2D.Double(b[0] - 7, b[1] - 7, 14, 14));
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new Ellipse2D.Double(b[0] - 4.4, b[1] - 4.8, 3.6, 2.6));
        }
        drawLeaf(g, 0.5, -13, -0.5 + Math.sin(time * 2) * 0.05, 1.0);
    }

    private void drawStrawberry(Graphics2D g) {
        Path2D body = new Path2D.Double();
        body.moveTo(-10, -6);
        body.curveTo(-12.5, 4, -4.5, 12, 0, 13.5);
        body.curveTo(4.5, 12, 12.5, 4, 10, -6);
        body.curveTo(8, -10.5, -8, -10.5, -10, -6);
        body.closePath();
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-3.5, -3), 20f, new float[] {0f, 1f},
                new Color[] {new Color(255, 112, 126), new Color(196, 18, 52)}));
        g.fill(body);
        g.setColor(new Color(150, 10, 40, 150));
        g.draw(body);
        g.setColor(new Color(255, 232, 130));
        double[][] seeds = {{-5, -3}, {0, -4}, {5, -3}, {-7, 2}, {-2.5, 1}, {2.5, 1}, {7, 2}, {-4, 6}, {0, 5.5},
            {4, 6}, {0, 10}};
        for (double[] s : seeds) {
            g.fill(new Ellipse2D.Double(s[0] - 0.9, s[1] - 1.3, 1.8, 2.6));
        }
        g.setColor(new Color(255, 255, 255, 140));
        g.fill(new Ellipse2D.Double(-8, -7, 5, 3.2));
        g.setPaint(new GradientPaint(0, -13, LEAF_LIGHT, 0, -5, LEAF_DARK));
        g.fill(starShape(0, -8.5, 8.6, 3.2, 6, -Math.PI / 2));
        g.setColor(new Color(86, 120, 40));
        g.fill(new RoundRectangle2D.Double(-1, -15, 2, 6, 2, 2));
    }

    private void drawGrapes(Graphics2D g) {
        double[][] balls = {{-7, -3}, {0, -3.5}, {7, -3}, {-3.6, 3.4}, {3.6, 3.4}, {0, 10}};
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(104, 76, 40));
        g.draw(new Line2D.Double(0, -8, 1.5, -14));
        drawLeaf(g, 1.5, -13.5, -0.7, 1.05);
        for (double[] b : balls) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(b[0] - 1.8, b[1] - 2), 9f, new float[] {0f, 1f},
                    new Color[] {new Color(214, 160, 255), new Color(92, 36, 150)}));
            g.fill(new Ellipse2D.Double(b[0] - 5.4, b[1] - 5.4, 10.8, 10.8));
            g.setColor(new Color(255, 255, 255, 160));
            g.fill(new Ellipse2D.Double(b[0] - 3.5, b[1] - 3.9, 2.6, 2));
        }
    }

    private void drawBanana(Graphics2D g) {
        g.rotate(-0.35);
        Path2D body = new Path2D.Double();
        body.moveTo(-11, -5);
        body.quadTo(0, 17, 12, -6);
        body.quadTo(5, -1, 0, -2.5);
        body.quadTo(-6, -2.5, -11, -5);
        body.closePath();
        g.setPaint(new GradientPaint(0, -4, new Color(255, 244, 140), 0, 9, new Color(238, 178, 18)));
        g.fill(body);
        g.setColor(new Color(160, 110, 10, 190));
        g.draw(body);
        g.setColor(new Color(255, 255, 255, 120));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D ridge = new Path2D.Double();
        ridge.moveTo(-7, -2);
        ridge.quadTo(0, 8, 8, -3);
        g.draw(ridge);
        g.setColor(new Color(96, 66, 30));
        g.fill(new Ellipse2D.Double(-12.8, -6.6, 3.6, 3.6));
        g.fill(new Ellipse2D.Double(10.4, -7.8, 3.4, 3.4));
    }

    private void drawLeaf(Graphics2D g, double x, double y, double angle, double size) {
        Graphics2D leaf = (Graphics2D) g.create();
        leaf.translate(x, y);
        leaf.rotate(angle);
        leaf.scale(size, size);
        Path2D shape = new Path2D.Double();
        shape.moveTo(0, 0);
        shape.curveTo(3, -5.5, 9, -5, 12, 0);
        shape.curveTo(8, 4.5, 3, 4.5, 0, 0);
        leaf.setPaint(new GradientPaint(0, -4, LEAF_LIGHT, 0, 4, LEAF_DARK));
        leaf.fill(shape);
        leaf.setColor(new Color(30, 100, 44, 170));
        leaf.setStroke(new BasicStroke(0.9f));
        leaf.draw(new Line2D.Double(1, 0, 10, 0));
        leaf.dispose();
    }

    private static Path2D starShape(double cx, double cy, double outer, double inner, int points, double rotation) {
        Path2D star = new Path2D.Double();
        for (int i = 0; i < points * 2; i++) {
            double a = rotation + i * Math.PI / points;
            double r = i % 2 == 0 ? outer : inner;
            double px = cx + Math.cos(a) * r;
            double py = cy + Math.sin(a) * r;
            if (i == 0) {
                star.moveTo(px, py);
            } else {
                star.lineTo(px, py);
            }
        }
        star.closePath();
        return star;
    }

    // ---- snake

    /**
     * Turns the grid cells of the snake into a smooth body: the cell centres are resampled at equal
     * spacing and then averaged with their neighbours, which rounds every corner into a curve.
     */
    private BodyPath buildBodyPath() {
        Cell[] cells = snake.toArray(new Cell[0]);
        double t = stepProgress();
        int total = cells.length + (prevTail != null ? 1 : 0);
        double[] px = new double[total];
        double[] py = new double[total];

        // The visible head slides from the previous head cell to the current one.
        px[0] = lerp(center(cells[1].x()), center(cells[0].x()), t);
        py[0] = lerp(center(cells[1].y()), center(cells[0].y()), t);
        for (int i = 1; i < cells.length; i++) {
            px[i] = center(cells[i].x());
            py[i] = center(cells[i].y());
        }
        if (prevTail != null) {   // the tail slides forward too, unless the snake just grew
            Cell last = cells[cells.length - 1];
            px[cells.length] = lerp(center(prevTail.x()), center(last.x()), t);
            py[cells.length] = lerp(center(prevTail.y()), center(last.y()), t);
        }

        double[] cumulative = new double[total];
        for (int i = 1; i < total; i++) {
            cumulative[i] = cumulative[i - 1] + Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
        }
        double length = cumulative[total - 1];
        int n = Math.max(3, (int) Math.round(length / BODY_STEP) + 1);
        double step = length / (n - 1);

        double[] xs = new double[n];
        double[] ys = new double[n];
        int segment = 1;
        for (int i = 0; i < n; i++) {
            double d = i * step;
            while (segment < total - 1 && cumulative[segment] < d) {
                segment++;
            }
            double segmentLength = cumulative[segment] - cumulative[segment - 1];
            double f = segmentLength > 1e-9 ? clamp01((d - cumulative[segment - 1]) / segmentLength) : 0;
            xs[i] = lerp(px[segment - 1], px[segment], f);
            ys[i] = lerp(py[segment - 1], py[segment], f);
        }

        for (int pass = 0; pass < 3; pass++) {
            double[] sx = xs.clone();
            double[] sy = ys.clone();
            for (int i = 1; i < n - 1; i++) {
                int w = Math.min(SMOOTH_WINDOW, Math.min(i, n - 1 - i));   // shrinks near the ends: they stay put
                double ax = 0;
                double ay = 0;
                for (int k = -w; k <= w; k++) {
                    ax += sx[i + k];
                    ay += sy[i + k];
                }
                xs[i] = ax / (2 * w + 1);
                ys[i] = ay / (2 * w + 1);
            }
        }

        // A gentle wave runs along the body while it moves.
        if (state == State.RUNNING) {
            double[] ox = xs.clone();
            double[] oy = ys.clone();
            for (int i = 1; i < n - 1; i++) {
                double tx = ox[i + 1] - ox[i - 1];
                double ty = oy[i + 1] - oy[i - 1];
                double len = Math.hypot(tx, ty);
                if (len < 1e-9) {
                    continue;
                }
                double d = i * step;
                double amplitude = 1.5 * Math.min(1, d / 50.0) * (1 - 0.6 * d / length);
                double wave = Math.sin(d * 0.07 - wiggleTime * 9) * amplitude;
                xs[i] += -ty / len * wave;
                ys[i] += tx / len * wave;
            }
        }
        lastBodyLength = length;
        return new BodyPath(xs, ys, n, step, length);
    }

    private void drawSnake(Graphics2D g) {
        BodyPath body = buildBodyPath();
        int n = body.count();
        double[] xs = body.x();
        double[] ys = body.y();
        Skin s = skin;

        double[] radius = new double[n];
        Color[] shade = new Color[n];
        double taperLength = Math.min(body.length() * 0.45, CELL * 4.0);   // the tail always narrows over the same distance
        for (int i = 0; i < n; i++) {
            double d = i * body.step();
            double t = d / body.length();
            double taper = lerp(0.32, 1.0, smoothStep((body.length() - d) / taperLength));
            double swallow = 0;
            for (double[] bulge : bulges) {
                double off = (d - bulge[0]) / 15.0;
                swallow += 4.2 * Math.exp(-off * off);
            }
            radius[i] = Math.max(2.5, BODY_RADIUS * taper + swallow);
            shade[i] = blend(s.main, s.dark, t * 0.9);
        }

        Path2D centerLine = new Path2D.Double();
        centerLine.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            centerLine.lineTo(xs[i], ys[i]);
        }

        Graphics2D shadow = (Graphics2D) g.create();
        shadow.translate(3, 5);
        shadow.setColor(new Color(0, 0, 0, 50));
        shadow.setStroke(new BasicStroke((float) (BODY_RADIUS * 2.0 + 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        shadow.draw(centerLine);
        shadow.dispose();

        Ellipse2D.Double dot = new Ellipse2D.Double();
        g.setColor(s.outline);
        for (int i = n - 1; i >= 0; i--) {   // tail first, so the head ends up on top
            double r = radius[i] + 2.4;
            dot.setFrame(xs[i] - r, ys[i] - r, r * 2, r * 2);
            g.fill(dot);
        }
        for (int i = n - 1; i >= 0; i--) {
            double r = radius[i];
            dot.setFrame(xs[i] - r, ys[i] - r, r * 2, r * 2);
            g.setColor(shade[i]);
            g.fill(dot);
        }
        for (int i = n - 1; i >= 0; i--) {   // lighter stripe along the middle of the back
            double r = radius[i] * 0.46;
            dot.setFrame(xs[i] - r, ys[i] - r, r * 2, r * 2);
            g.setColor(blend(shade[i], s.belly, 0.30));
            g.fill(dot);
        }

        // Diamond scales along the back.
        int every = (int) Math.round(22 / body.step());
        for (int i = every; i < n - 3; i += every) {
            double t = i * body.step() / body.length();
            if (t > 0.92) {
                break;
            }
            int a = Math.max(0, i - 2);
            int b = Math.min(n - 1, i + 2);
            double dx = xs[a] - xs[b];
            double dy = ys[a] - ys[b];
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) {
                continue;
            }
            double ux = dx / len;
            double uy = dy / len;
            double along = radius[i] * 0.62;
            double across = radius[i] * 0.5;
            Path2D diamond = new Path2D.Double();
            diamond.moveTo(xs[i] + ux * along, ys[i] + uy * along);
            diamond.lineTo(xs[i] - uy * across, ys[i] + ux * across);
            diamond.lineTo(xs[i] - ux * along, ys[i] - uy * along);
            diamond.lineTo(xs[i] + uy * across, ys[i] - ux * across);
            diamond.closePath();
            g.setColor(withAlpha(blend(shade[i], s.dark, 0.7), 150));
            g.fill(diamond);
        }

        // Glossy highlight on the top of the body.
        int shineCount = (int) (n * 0.62);
        if (shineCount > 3) {
            Path2D shine = new Path2D.Double();
            shine.moveTo(xs[0], ys[0]);
            for (int i = 1; i < shineCount; i++) {
                shine.lineTo(xs[i], ys[i]);
            }
            Graphics2D gloss = (Graphics2D) g.create();
            gloss.translate(-2, -4);
            gloss.setColor(new Color(255, 255, 255, 62));
            gloss.setStroke(new BasicStroke((float) (BODY_RADIUS * 0.55), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gloss.draw(shine);
            gloss.dispose();
        }

        int lookBack = Math.min(n - 1, 5);
        double heading = Math.atan2(ys[0] - ys[lookBack], xs[0] - xs[lookBack]);
        drawHead(g, xs[0], ys[0], heading, BODY_RADIUS * 1.12);
    }

    private void drawHead(Graphics2D g, double x, double y, double heading, double r) {
        Skin s = skin;
        boolean dead = state == State.GAME_OVER && !won;
        boolean happy = state == State.GAME_OVER && won;

        Graphics2D h = (Graphics2D) g.create();
        h.translate(x, y);
        h.rotate(heading);

        // Tongue flicks out every couple of seconds while the mouth is closed.
        double flick = animTime % 2.4;
        if (!dead && mouth < 0.3 && flick < 0.45) {
            double len = CELL * 0.85 * Math.sin(flick / 0.45 * Math.PI);
            double sx = r * 1.25;
            double ex = sx + len;
            double fork = len * 0.34;
            h.setColor(TONGUE);
            h.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            h.draw(new Line2D.Double(sx - 3, 0, ex, 0));
            h.draw(new Line2D.Double(ex, 0, ex + fork * 0.8, -fork * 0.7));
            h.draw(new Line2D.Double(ex, 0, ex + fork * 0.8, fork * 0.7));
        }

        // Head shape: a rounded, slightly pointed oval.
        double outline = 2.4;
        h.setColor(s.outline);
        h.fill(new Ellipse2D.Double(-r * 1.0 - outline, -r * 1.05 - outline, r * 2.4 + outline * 2, r * 2.1 + outline * 2));
        // light comes from the top-left of the screen, whatever way the head is turned
        double lx = -0.3 * r * Math.cos(heading) + -0.4 * r * Math.sin(heading);
        double ly = 0.3 * r * Math.sin(heading) + -0.4 * r * Math.cos(heading);
        h.setPaint(new RadialGradientPaint(new Point2D.Double(r * 0.2 + lx, ly), (float) (r * 1.9),
                new float[] {0f, 1f}, new Color[] {s.head, s.main}));
        h.fill(new Ellipse2D.Double(-r * 1.0, -r * 1.05, r * 2.4, r * 2.1));

        // Rosy cheeks.
        h.setColor(new Color(255, 120, 150, 90));
        h.fill(new Ellipse2D.Double(r * 0.15, -r * 1.0, r * 0.62, r * 0.42));
        h.fill(new Ellipse2D.Double(r * 0.15, r * 0.58, r * 0.62, r * 0.42));

        // Mouth opening as a fruit gets close.
        if (mouth > 0.05 && !dead) {
            double open = mouth;
            h.setColor(new Color(92, 18, 40));
            h.fill(new Ellipse2D.Double(r * 0.78, -r * 0.62 * open, r * 0.78, r * 1.24 * open));
            h.setColor(new Color(255, 120, 140));
            h.fill(new Ellipse2D.Double(r * 0.92, -r * 0.2 * open, r * 0.5, r * 0.4 * open));
        } else {
            h.setColor(new Color(16, 28, 70, 160));
            h.fill(new Ellipse2D.Double(r * 1.2, -r * 0.34, 2.4, 2.4));
            h.fill(new Ellipse2D.Double(r * 1.2, r * 0.34 - 2.4, 2.4, 2.4));
        }

        // Eyes look towards the fruit.
        double pupilX = 0;
        double pupilY = 0;
        if (food != null) {
            double wx = center(food.x()) - x;
            double wy = center(food.y()) - y;
            double lxp = wx * Math.cos(-heading) - wy * Math.sin(-heading);
            double lyp = wx * Math.sin(-heading) + wy * Math.cos(-heading);
            double len = Math.hypot(lxp, lyp);
            if (len > 1e-6) {
                pupilX = lxp / len;
                pupilY = lyp / len;
            }
        }
        boolean blink = (animTime + 2.0) % 3.8 < 0.13;
        double eyeR = r * 0.42;
        for (int side = -1; side <= 1; side += 2) {
            double ex = r * 0.42;
            double ey = side * r * 0.64;
            if (dead) {
                h.setColor(new Color(20, 30, 70));
                h.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                h.draw(new Line2D.Double(ex - 3.6, ey - 3.6, ex + 3.6, ey + 3.6));
                h.draw(new Line2D.Double(ex - 3.6, ey + 3.6, ex + 3.6, ey - 3.6));
            } else if (happy) {
                h.setColor(new Color(20, 30, 70));
                h.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                h.draw(new Arc2D.Double(ex - 4.4, ey - 3, 8.8, 7.5, 200, 140, Arc2D.OPEN));
            } else if (blink) {
                h.setColor(new Color(20, 30, 70));
                h.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                h.draw(new Line2D.Double(ex, ey - eyeR * 0.9, ex, ey + eyeR * 0.9));
            } else {
                h.setColor(new Color(20, 30, 70, 120));
                h.fill(new Ellipse2D.Double(ex - eyeR - 1.2, ey - eyeR - 1.2, eyeR * 2 + 2.4, eyeR * 2 + 2.4));
                h.setColor(Color.WHITE);
                h.fill(new Ellipse2D.Double(ex - eyeR, ey - eyeR, eyeR * 2, eyeR * 2));
                double pupil = eyeR * 0.58;
                double px = ex + pupilX * eyeR * 0.36;
                double py = ey + pupilY * eyeR * 0.36;
                h.setColor(new Color(14, 20, 44));
                h.fill(new Ellipse2D.Double(px - pupil, py - pupil, pupil * 2, pupil * 2));
                h.setColor(Color.WHITE);
                h.fill(new Ellipse2D.Double(px - pupil * 0.7, py - pupil * 0.8, pupil * 0.7, pupil * 0.7));
            }
        }
        h.dispose();

        if (dead) {   // dizzy stars circle above the head
            for (int i = 0; i < 3; i++) {
                double a = animTime * 5 + i * Math.PI * 2 / 3;
                g.setColor(GOLD);
                g.fill(starShape(x + Math.cos(a) * r * 1.5, y - r * 1.5 + Math.sin(a) * r * 0.45, 5, 2, 5, a));
            }
        }
    }

    // ---- effects

    private void drawEffects(Graphics2D g) {
        for (Ring r : rings) {
            double progress = 1 - r.life / r.maxLife;
            double radius = r.maxRadius * (0.25 + 0.75 * (1 - Math.pow(1 - progress, 3)));
            g.setColor(withAlpha(r.color, (int) (210 * (1 - progress))));
            g.setStroke(new BasicStroke((float) (4 * (1 - progress) + 1)));
            g.draw(new Ellipse2D.Double(r.x - radius, r.y - radius, radius * 2, radius * 2));
        }
        for (Particle p : particles) {
            double a = clamp01(p.life / p.maxLife);
            double size = p.size * (0.4 + 0.6 * a);
            g.setColor(withAlpha(p.color, (int) (255 * a)));
            if (p.kind == Particle.STAR) {
                g.fill(starShape(p.x, p.y, size, size * 0.4, 4, animTime * 4 + p.x));
            } else {
                g.fill(new Ellipse2D.Double(p.x - size / 2, p.y - size / 2, size, size));
            }
        }
    }

    private void sparkle(Graphics2D g, double x, double y, double size, int alpha) {
        g.setColor(new Color(255, 250, 215, Math.max(0, Math.min(255, alpha))));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x - size, y, x + size, y));
        g.draw(new Line2D.Double(x, y - size, x, y + size));
    }

    private void drawFloatTexts(Graphics2D g) {
        for (FloatText f : floatTexts) {
            int alpha = (int) (255 * Math.min(1.0, clamp01(f.life / f.maxLife) * 1.8));
            g.setFont(f.font);
            float x = (float) (f.x - g.getFontMetrics().stringWidth(f.text) / 2.0);
            g.setColor(new Color(0, 0, 0, alpha / 2));
            g.drawString(f.text, x + 2, (float) f.y + 2);
            g.setColor(withAlpha(f.color, alpha));
            g.drawString(f.text, x, (float) f.y);
        }
    }

    // ---- HUD and overlay

    private void drawHud(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, HUD_LIGHT, 0, HUD_HEIGHT, HUD_DARK));
        g.fillRect(0, 0, VIEW_W, HUD_HEIGHT);
        g.setColor(new Color(0, 0, 0, 60));
        g.fillRect(0, HUD_HEIGHT - 3, VIEW_W, 3);

        // A different fruit icon every couple of seconds, then the score.
        Fruit icon = REGULAR_FRUITS[(int) (animTime / 1.8) % REGULAR_FRUITS.length];
        drawFruit(g, icon, 36, 33, 1.0, animTime);
        Graphics2D scoreText = (Graphics2D) g.create();
        double pop = 1 + 0.35 * scorePop;
        scoreText.translate(62, 44);
        scoreText.scale(pop, pop);
        scoreText.setFont(FONT_BIG);
        scoreText.setColor(Color.WHITE);
        scoreText.drawString(String.valueOf(score), 0, 0);
        scoreText.dispose();

        g.setFont(FONT_TAG);
        g.setColor(new Color(196, 228, 160));
        g.drawString("BEST", 170, 27);
        g.setFont(FONT_MEDIUM);
        g.setColor(GOLD);
        g.drawString(String.valueOf(highScore), 170, 49);

        g.setFont(FONT_TAG);
        g.setColor(new Color(196, 228, 160));
        g.drawString("LEVEL " + level, 290, 27);
        double progress = (fruitsEaten % FRUITS_PER_LEVEL) / (double) FRUITS_PER_LEVEL;
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new RoundRectangle2D.Double(290, 35, 130, 11, 11, 11));
        g.setPaint(new GradientPaint(290, 0, new Color(255, 232, 110), 420, 0, new Color(255, 160, 50)));
        g.fill(new RoundRectangle2D.Double(290, 35, Math.max(11, 130 * progress), 11, 11, 11));

        if (combo >= 2 && comboTimeLeft > 0) {
            double beat = 1 + 0.06 * Math.sin(animTime * 14);
            Graphics2D comboText = (Graphics2D) g.create();
            comboText.translate(448, 29);
            comboText.scale(beat, beat);
            comboText.setFont(FONT_MEDIUM);
            comboText.setColor(new Color(255, 170, 60));
            comboText.drawString("COMBO x" + combo, 0, 0);
            comboText.dispose();
            g.setColor(new Color(0, 0, 0, 80));
            g.fill(new RoundRectangle2D.Double(448, 36, 120, 9, 9, 9));
            g.setColor(new Color(255, 120, 60));
            g.fill(new RoundRectangle2D.Double(448, 36, Math.max(9, 120 * comboTimeLeft / COMBO_WINDOW), 9, 9, 9));
        }

        int right = VIEW_W - 16;
        right = drawPill(g, sounds.label(), right, sounds.isOn() ? new Color(60, 150, 90) : new Color(150, 70, 70));
        if (sounds.isAvailable()) {
            right = drawPill(g, sounds.musicLabel(), right,
                    sounds.musicOn() && sounds.isOn() ? new Color(70, 120, 190) : new Color(110, 110, 130));
        }
        right = drawPill(g, "C  SKIN", right, skin.main);
        if (autoPlay) {
            drawPill(g, "BOT", right, new Color(230, 150, 30));
        }
    }

    private int drawPill(Graphics2D g, String text, int right, Color background) {
        g.setFont(FONT_TAG);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 24;
        int h = 26;
        int x = right - w;
        int y = (HUD_HEIGHT - h) / 2;
        g.setPaint(new GradientPaint(0, y, brighten(background, 0.2f), 0, y + h, background));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
        g.setColor(new Color(255, 255, 255, 70));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, h, h));
        g.setColor(Color.WHITE);
        g.drawString(text, x + 12, y + (h + fm.getAscent() - fm.getDescent()) / 2);
        return x - 10;
    }

    private void drawOverlay(Graphics2D g) {
        if (state == State.RUNNING) {
            return;
        }
        String title;
        String subtitle;
        String[] hints;
        Color titleColor;
        switch (state) {
            case READY -> {
                title = "SnakeGame";
                titleColor = new Color(255, 240, 150);
                subtitle = "Press SPACE or an arrow key to start";
                hints = new String[] {"Eat fruit fast to build a COMBO and score more!",
                    "Arrows / WASD: move     P: pause     R: restart",
                    "B: bot     M: sound     N: music     C: snake colour"};
            }
            case PAUSED -> {
                title = "PAUSED";
                titleColor = Color.WHITE;
                subtitle = "Press P to resume";
                hints = new String[] {};
            }
            default -> {
                title = won ? "YOU WIN!" : "GAME OVER";
                titleColor = won ? GOLD : new Color(255, 120, 110);
                subtitle = "Score " + score + "     Best " + highScore + (newBest ? "     NEW BEST!" : "");
                hints = new String[] {fruitsEaten + " fruits eaten", "Press SPACE to play again"};
            }
        }

        g.setColor(new Color(10, 30, 10, state == State.READY ? 55 : 120));
        g.fillRect(0, HUD_HEIGHT, BOARD_W, BOARD_H);

        // Text sits on a dark card in the upper half, so the snake below stays visible on the start screen.
        int cardW = 680;
        int cardH = 150 + hints.length * 26 + (state == State.READY ? 56 : 0);
        int cardX = (VIEW_W - cardW) / 2;
        int cardY = HUD_HEIGHT + 56;
        g.setPaint(new GradientPaint(0, cardY, new Color(18, 44, 22, 232), 0, cardY + cardH, new Color(10, 28, 14, 240)));
        g.fill(new RoundRectangle2D.Double(cardX, cardY, cardW, cardH, 40, 40));
        g.setColor(new Color(255, 255, 255, 46));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new RoundRectangle2D.Double(cardX + 0.5, cardY + 0.5, cardW - 1, cardH - 1, 40, 40));

        double pulse = 1 + 0.035 * Math.sin(animTime * 3.5);
        Graphics2D t = (Graphics2D) g.create();
        t.translate(VIEW_W / 2.0, cardY + 76);
        t.scale(pulse, pulse);
        t.setFont(FONT_TITLE);
        int w = t.getFontMetrics().stringWidth(title);
        t.setColor(new Color(0, 0, 0, 130));
        t.drawString(title, -w / 2 + 4, 4);
        t.setColor(titleColor);
        t.drawString(title, -w / 2, 0);
        t.dispose();

        drawCentered(g, FONT_BIG, subtitle, cardY + 124, Color.WHITE);
        for (int i = 0; i < hints.length; i++) {
            boolean last = state != State.READY && i == hints.length - 1;
            drawCentered(g, last ? FONT_MEDIUM : FONT_SMALL, hints[i], cardY + 158 + i * 26,
                    last ? GOLD : new Color(208, 236, 192));
        }
        if (state == State.READY) {   // the fruit line-up
            int count = REGULAR_FRUITS.length;
            for (int i = 0; i < count; i++) {
                double x = VIEW_W / 2.0 + (i - (count - 1) / 2.0) * 74;
                double y = cardY + cardH - 38 + Math.sin(animTime * 3 + i * 0.9) * 4;
                drawFruit(g, REGULAR_FRUITS[i], x, y, 1.25, animTime + i);
            }
        }
    }

    private void drawCentered(Graphics2D g, Font font, String text, int y, Color color) {
        g.setFont(font);
        int x = (VIEW_W - g.getFontMetrics().stringWidth(text)) / 2;
        g.setColor(new Color(0, 0, 0, 110));
        g.drawString(text, x + 2, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    // ----------------------------------------------------------------- Helpers

    private static double center(int cell) {
        return (cell + 0.5) * CELL;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double smoothStep(double t) {
        double c = clamp01(t);
        return c * c * (3 - 2 * c);
    }

    /** Overshoots slightly before settling: makes things "pop" into view. */
    private static double easeOutBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color blend(Color a, Color b, double t) {
        double k = clamp01(t);
        return new Color((int) lerp(a.getRed(), b.getRed(), k), (int) lerp(a.getGreen(), b.getGreen(), k),
                (int) lerp(a.getBlue(), b.getBlue(), k));
    }

    private static Color brighten(Color c, float amount) {
        return blend(c, Color.WHITE, amount);
    }

    // ------------------------------------------------------------------- Sound

    /**
     * All sound is generated from scratch: every effect is a few layered tones (sine, square, triangle,
     * saw, plucks, bells and noise) rendered to 16-bit audio. A small mixer thread adds everything that is
     * playing, plus the looping background tune, and streams it to one audio line, so sounds can overlap.
     * If the machine has no audio output the game simply runs silently.
     */
    private static final class Sounds {

        private static final float RATE = 44_100f;
        private static final int CHUNK = 512;               // frames mixed per cycle (about 12 ms)
        private static final int MAX_VOICES = 24;
        private static final double MUSIC_GAIN = 0.5;
        private static final double[] PITCHES = {1.0, 0.94, 1.07};

        /** C major pentatonic, two octaves: the eating sound climbs this scale as the combo grows. */
        private static final double[] NOTES = {
            523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66, 1318.51, 1567.98};

        private enum Wave { SINE, SQUARE, TRIANGLE, SAW, HISS, SOFT_NOISE, PLUCK, BELL }

        private record Tone(double fromHz, double toHz, double startMs, double millis, double volume, Wave wave) { }

        private static final class Voice {
            final short[] data;
            int position;

            Voice(short[] data) {
                this.data = data;
            }
        }

        private final Map<Sfx, short[][]> library = new EnumMap<>(Sfx.class);
        private final short[][][] eatLibrary = new short[Flavor.values().length][NOTES.length][];
        private final long[] lastPlayed = new long[Sfx.values().length];
        private final List<Voice> voices = new ArrayList<>();
        private final Random random = new Random();
        private SourceDataLine line;
        private volatile short[] music;
        private volatile boolean available;
        private volatile boolean muted;
        private volatile boolean musicOn = true;
        private volatile boolean musicQuiet = true;
        private int musicPosition;
        private double musicGain;

        Sounds() {
            buildEffects();
            openAudio();
        }

        // ---- public API

        void play(Sfx sfx) {
            short[][] variants = library.get(sfx);
            if (variants == null) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastPlayed[sfx.ordinal()] < sfx.minGapMs * 1_000_000L) {
                return;
            }
            lastPlayed[sfx.ordinal()] = now;
            enqueue(variants[random.nextInt(variants.length)]);
        }

        /** The eating sound: a fruit-specific crunch / squish / pop plus the next note of the combo melody. */
        void playEat(Flavor flavor, int combo) {
            int note = Math.max(0, Math.min(NOTES.length - 1, combo - 1));
            enqueue(eatLibrary[flavor.ordinal()][note]);
        }

        void toggleMute() {
            if (available) {
                muted = !muted;
                if (muted) {
                    synchronized (voices) {
                        voices.clear();
                    }
                }
            }
        }

        void toggleMusic() {
            musicOn = !musicOn;
        }

        /** The tune plays softer while the game is not running (title screen, pause, game over). */
        void setMusicQuiet(boolean quiet) {
            musicQuiet = quiet;
        }

        boolean isAvailable() {
            return available;
        }

        boolean isOn() {
            return available && !muted;
        }

        boolean musicOn() {
            return musicOn;
        }

        String label() {
            if (!available) {
                return "NO AUDIO";
            }
            return muted ? "SOUND OFF" : "SOUND ON";
        }

        String musicLabel() {
            return musicOn ? "MUSIC ON" : "MUSIC OFF";
        }

        private void enqueue(short[] data) {
            if (!available || muted || data == null) {
                return;
            }
            synchronized (voices) {
                if (voices.size() >= MAX_VOICES) {
                    voices.remove(0);
                }
                voices.add(new Voice(data));
            }
        }

        // ---- audio output

        private void openAudio() {
            try {
                AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, 4096);
                line.start();
                available = true;
                Thread mixer = new Thread(this::mixLoop, "snake-sound-mixer");
                mixer.setDaemon(true);
                mixer.start();
            } catch (Exception e) {   // no sound card, headless machine, line busy...
                available = false;
                System.err.println("Sound disabled: " + e.getMessage());
            }
        }

        private void mixLoop() {
            music = renderMusic();   // takes a moment, so it is done here and not while the window opens
            int[] mix = new int[CHUNK];
            byte[] out = new byte[CHUNK * 2];
            try {
                while (true) {
                    mixChunk(mix);
                    for (int i = 0; i < CHUNK; i++) {
                        // soft limiter: loud moments get squashed gently instead of crackling
                        int sample = (int) (32767 * Math.tanh(mix[i] / 32768.0));
                        out[2 * i] = (byte) sample;
                        out[2 * i + 1] = (byte) (sample >> 8);
                    }
                    line.write(out, 0, out.length);
                }
            } catch (RuntimeException e) {
                available = false;
            }
        }

        /** Adds up every playing effect and the background tune for the next 512 frames. */
        private void mixChunk(int[] mix) {
            Arrays.fill(mix, 0);
            synchronized (voices) {
                for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
                    Voice v = it.next();
                    int n = Math.min(CHUNK, v.data.length - v.position);
                    for (int i = 0; i < n; i++) {
                        mix[i] += v.data[v.position + i];
                    }
                    v.position += n;
                    if (v.position >= v.data.length) {
                        it.remove();
                    }
                }
            }
            short[] loop = music;
            if (loop != null) {
                double target = musicOn && !muted ? (musicQuiet ? 0.35 : 1.0) * MUSIC_GAIN : 0;
                for (int i = 0; i < CHUNK; i++) {
                    musicGain += (target - musicGain) * 0.0004;   // fades smoothly: no clicks
                    mix[i] += (int) (loop[musicPosition] * musicGain);
                    if (++musicPosition >= loop.length) {
                        musicPosition = 0;
                    }
                }
            }
        }

        // ---- the sound effects

        private void buildEffects() {
            single(Sfx.START, pluck(0, 392.00, 260, 0.30), pluck(90, 523.25, 260, 0.30),
                    pluck(180, 659.25, 260, 0.30), pluck(270, 783.99, 260, 0.30),
                    bell(360, 1046.50, 520, 0.18), tone(360, 523.25, 360, 0.10, Wave.TRIANGLE));
            single(Sfx.PAUSE, pluck(0, 784, 200, 0.28), pluck(110, 523, 280, 0.28));
            single(Sfx.RESUME, pluck(0, 523, 200, 0.28), pluck(110, 784, 280, 0.28));
            single(Sfx.LEVEL_UP, pluck(0, 523.25, 300, 0.28), pluck(70, 659.25, 300, 0.28),
                    pluck(140, 783.99, 300, 0.28), pluck(210, 1046.50, 300, 0.28),
                    bell(280, 1318.51, 700, 0.20), tone(280, 523.25, 520, 0.10, Wave.TRIANGLE),
                    tone(280, 659.25, 520, 0.08, Wave.TRIANGLE), tone(280, 783.99, 520, 0.08, Wave.TRIANGLE));
            single(Sfx.GOLD, bell(0, 1318.51, 420, 0.22), bell(55, 1567.98, 420, 0.22), bell(110, 2093.00, 480, 0.22),
                    bell(165, 2637.02, 640, 0.20), sweep(0, 900, 3200, 380, 0.07, Wave.SINE),
                    hiss(0, 70, 0.10), pluck(0, 523.25, 360, 0.18));
            single(Sfx.COMBO_END, sweep(0, 740, 280, 160, 0.16, Wave.SINE), pluck(0, 330, 200, 0.12));
            single(Sfx.CRASH, softNoise(0, 260, 0.45), sweep(0, 260, 70, 300, 0.45, Wave.SINE),
                    hiss(0, 40, 0.20), sweep(150, 392, 330, 240, 0.16, Wave.SAW),
                    sweep(390, 330, 262, 240, 0.16, Wave.SAW), sweep(630, 262, 196, 240, 0.16, Wave.SAW),
                    sweep(870, 196, 98, 560, 0.18, Wave.SAW));
            single(Sfx.WIN, pluck(0, 523.25, 300, 0.26), pluck(110, 659.25, 300, 0.26), pluck(220, 783.99, 300, 0.26),
                    pluck(330, 1046.50, 300, 0.26), pluck(440, 1318.51, 300, 0.26),
                    bell(550, 1567.98, 900, 0.24), tone(550, 523.25, 800, 0.10, Wave.TRIANGLE),
                    tone(550, 659.25, 800, 0.08, Wave.TRIANGLE), tone(550, 783.99, 800, 0.08, Wave.TRIANGLE));
            single(Sfx.BOT_ON, sweep(0, 500, 1000, 130, 0.30, Wave.SINE), pluck(100, 1000, 160, 0.14));
            single(Sfx.BOT_OFF, sweep(0, 1000, 500, 130, 0.30, Wave.SINE));
            single(Sfx.CLICK, pluck(0, 880, 90, 0.22), hiss(0, 8, 0.10));
            varied(Sfx.TURN, sweep(0, 760, 520, 24, 0.06, Wave.TRIANGLE), softNoise(0, 26, 0.045));

            for (Flavor flavor : Flavor.values()) {
                for (int note = 0; note < NOTES.length; note++) {
                    eatLibrary[flavor.ordinal()][note] = render(1.0, eatTones(flavor, NOTES[note]));
                }
            }
        }

        private static Tone[] eatTones(Flavor flavor, double hz) {
            List<Tone> tones = new ArrayList<>();
            switch (flavor) {
                case CRUNCH -> {
                    for (double start : new double[] {0, 13, 29, 41}) {
                        tones.add(hiss(start, 9, 0.30));
                    }
                    tones.add(sweep(0, 260, 120, 55, 0.25, Wave.SINE));
                }
                case JUICY -> {
                    tones.add(sweep(0, 420, 150, 70, 0.26, Wave.SINE));
                    for (double start : new double[] {0, 22, 46}) {
                        tones.add(sweep(start, 350, 950, 18, 0.14, Wave.SINE));
                    }
                    tones.add(softNoise(0, 70, 0.22));
                }
                case POP -> {
                    tones.add(sweep(0, 260, 1000, 38, 0.30, Wave.SINE));
                    tones.add(hiss(0, 6, 0.26));
                    tones.add(sweep(38, 1000, 420, 30, 0.12, Wave.SINE));
                }
                case SOFT -> {
                    tones.add(sweep(0, 200, 110, 90, 0.36, Wave.SINE));
                    tones.add(softNoise(0, 80, 0.16));
                    tones.add(hiss(5, 8, 0.12));
                }
            }
            tones.add(pluck(10, hz, 300, 0.32));
            tones.add(pluck(10, hz * 2, 220, 0.10));
            tones.add(bell(10, hz * 3, 180, 0.04));
            return tones.toArray(new Tone[0]);
        }

        private void single(Sfx sfx, Tone... tones) {
            library.put(sfx, new short[][] {render(1.0, tones)});
        }

        private void varied(Sfx sfx, Tone... tones) {
            short[][] variants = new short[PITCHES.length][];
            for (int i = 0; i < PITCHES.length; i++) {
                variants[i] = render(PITCHES[i], tones);
            }
            library.put(sfx, variants);
        }

        private static Tone tone(double startMs, double hz, double millis, double volume, Wave wave) {
            return new Tone(hz, hz, startMs, millis, volume, wave);
        }

        private static Tone sweep(double startMs, double fromHz, double toHz, double millis, double volume, Wave wave) {
            return new Tone(fromHz, toHz, startMs, millis, volume, wave);
        }

        private static Tone pluck(double startMs, double hz, double millis, double volume) {
            return new Tone(hz, hz, startMs, millis, volume, Wave.PLUCK);
        }

        private static Tone bell(double startMs, double hz, double millis, double volume) {
            return new Tone(hz, hz, startMs, millis, volume, Wave.BELL);
        }

        private static Tone hiss(double startMs, double millis, double volume) {
            return new Tone(0, 0, startMs, millis, volume, Wave.HISS);
        }

        private static Tone softNoise(double startMs, double millis, double volume) {
            return new Tone(0, 0, startMs, millis, volume, Wave.SOFT_NOISE);
        }

        // ---- the background tune

        /**
         * An 8-bar loop at 124 BPM in C major: kick, snare and hi-hats, a bouncing bass, a sparkly arpeggio and
         * a made-up pentatonic melody over the chords C - Am - F - G. Tones that ring past the end of the loop
         * wrap around to the start, so the loop is seamless.
         */
        private static short[] renderMusic() {
            double stepMs = 60_000.0 / 124 / 4;   // one sixteenth note
            int bars = 8;
            int stepsPerBar = 16;
            int loopSamples = (int) (RATE * bars * stepsPerBar * stepMs / 1000.0);
            float[] buffer = new float[loopSamples];
            Random noise = new Random(5);

            double[][] chords = {
                {261.63, 329.63, 392.00}, {220.00, 261.63, 329.63}, {174.61, 220.00, 261.63}, {196.00, 246.94, 293.66}};
            double[] roots = {130.81, 110.00, 87.31, 98.00};
            double[] bounce = {1, 2, 1, 2, 1, 2, 1.5, 2};
            double[] penta = {523.25, 587.33, 659.25, 783.99, 880.00};
            int[][] chordPenta = {{0, 2, 3}, {4, 0, 2}, {4, 0}, {3, 1}};   // pentatonic notes that fit each chord
            int[] arpPattern = {0, 1, 2, 1};
            Random melody = new Random(42);
            int pos = 2;                                                    // position in a two-octave pentatonic scale

            for (int bar = 0; bar < bars; bar++) {
                int chord = bar % 4;
                for (int step = 0; step < stepsPerBar; step++) {
                    double t = (bar * stepsPerBar + step) * stepMs;

                    // Everything sits in the range laptop speakers can actually play (above about 150 Hz).
                    if (step == 0 || step == 8 || (step == 14 && bar % 2 == 1)) {
                        addTone(buffer, sweep(t, 170, 62, 110, 0.20, Wave.SINE), 1.0, true, noise);
                    }
                    if (step == 4 || step == 12) {
                        addTone(buffer, hiss(t, 95, 0.09), 1.0, true, noise);
                        addTone(buffer, sweep(t, 230, 160, 55, 0.10, Wave.TRIANGLE), 1.0, true, noise);
                    }
                    if (step % 2 == 0) {
                        addTone(buffer, hiss(t, step == 14 ? 90 : 24, 0.05), 1.0, true, noise);
                        double note = roots[chord] * 2 * bounce[step / 2];
                        addTone(buffer, tone(t, note, 125, 0.075, Wave.TRIANGLE), 1.0, true, noise);
                    } else {
                        double note = chords[chord][arpPattern[(step / 2) % arpPattern.length]] * 2;
                        addTone(buffer, tone(t, note, 95, 0.055, Wave.SQUARE), 1.0, true, noise);
                    }

                    boolean strong = step == 0 || step == 8;
                    boolean playsNote = strong || (step % 2 == 0 && melody.nextDouble() < 0.55)
                            || (step % 2 == 1 && melody.nextDouble() < 0.18);
                    if (playsNote) {
                        if (strong) {
                            int[] allowed = chordPenta[chord];
                            pos = allowed[melody.nextInt(allowed.length)] + (melody.nextDouble() < 0.4 ? 5 : 0);
                        } else {
                            pos = Math.max(0, Math.min(9, pos + melody.nextInt(5) - 2));
                        }
                        double hz = pos >= 5 ? penta[pos - 5] * 2 : penta[pos];
                        addTone(buffer, pluck(t, hz, 330, 0.20), 1.0, true, noise);
                    }
                }
            }

            // Scale to a steady loudness (and keep the peaks below clipping).
            double peak = 0.0001;
            double sumSquares = 0;
            for (float v : buffer) {
                peak = Math.max(peak, Math.abs(v));
                sumSquares += v * v;
            }
            double rms = Math.sqrt(sumSquares / buffer.length);
            double scale = Math.min(0.9 / peak, 0.14 / Math.max(rms, 0.0001));
            short[] pcm = new short[loopSamples];
            for (int i = 0; i < loopSamples; i++) {
                pcm[i] = (short) (buffer[i] * scale * Short.MAX_VALUE);
            }
            return pcm;
        }

        // ---- synthesis

        /** Mixes all tones of one effect into 16-bit mono audio. */
        private static short[] render(double pitch, Tone... tones) {
            int total = 0;
            for (Tone tone : tones) {
                total = Math.max(total, (int) Math.ceil(RATE * (tone.startMs() + tone.millis()) / 1000.0));
            }
            float[] buffer = new float[total];
            Random noise = new Random(11);
            for (Tone tone : tones) {
                addTone(buffer, tone, pitch, false, noise);
            }
            short[] pcm = new short[total];
            for (int i = 0; i < total; i++) {
                pcm[i] = (short) (Math.max(-1f, Math.min(1f, buffer[i])) * Short.MAX_VALUE);
            }
            return pcm;
        }

        /** Adds one tone to the buffer. With `wrap`, the part that runs past the end continues at the start. */
        private static void addTone(float[] buffer, Tone tone, double pitch, boolean wrap, Random noise) {
            int start = (int) (RATE * tone.startMs() / 1000.0);
            int samples = (int) (RATE * tone.millis() / 1000.0);
            boolean noisy = tone.wave() == Wave.HISS || tone.wave() == Wave.SOFT_NOISE;
            int attack = Math.max(1, (int) (RATE * (noisy ? 0.0005 : 0.003)));
            int release = Math.max(1, (int) (samples * 0.35));
            double phase = 0;
            double smooth = 0;

            for (int i = 0; i < samples; i++) {
                int index = start + i;
                if (index >= buffer.length) {
                    if (!wrap) {
                        break;
                    }
                    index %= buffer.length;
                }
                double progress = i / (double) samples;
                double hz = (tone.fromHz() + (tone.toHz() - tone.fromHz()) * progress) * pitch;
                phase += 2 * Math.PI * hz / RATE;

                double raw;
                double envelope = Math.min(1.0, i / (double) attack);
                switch (tone.wave()) {
                    case SINE -> raw = Math.sin(phase);
                    case SQUARE -> raw = Math.sin(phase) >= 0 ? 1.0 : -1.0;
                    case TRIANGLE -> raw = 2 / Math.PI * Math.asin(Math.sin(phase));
                    case SAW -> {
                        double cycle = phase / (2 * Math.PI);
                        raw = 2 * (cycle - Math.floor(cycle)) - 1;
                    }
                    case PLUCK -> raw = 0.72 * (Math.sin(phase) + 0.42 * Math.sin(2 * phase) + 0.2 * Math.sin(3 * phase));
                    case BELL -> raw = 0.62 * (Math.sin(phase) + 0.55 * Math.sin(2.76 * phase) + 0.3 * Math.sin(5.4 * phase));
                    case HISS -> raw = noise.nextDouble() * 2 - 1;
                    default -> {
                        smooth += (noise.nextDouble() * 2 - 1 - smooth) * 0.35;   // softened noise
                        raw = smooth * 1.9;
                    }
                }
                switch (tone.wave()) {
                    case HISS, SOFT_NOISE -> envelope *= Math.pow(1 - progress, 2.4);   // noise bursts die away quickly
                    case PLUCK -> envelope *= Math.exp(-4.6 * progress);
                    case BELL -> envelope *= Math.exp(-3.8 * progress);
                    default -> {
                        if (i > samples - release) {
                            envelope *= (samples - i) / (double) release;
                        }
                    }
                }
                buffer[index] += (float) (tone.volume() * envelope * raw);
            }
        }
    }

    // ------------------------------------------------------------------- Main

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SnakeGame game = new SnakeGame();

            JFrame frame = new JFrame("SnakeGame");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(game);
            frame.pack();
            frame.setMinimumSize(new Dimension(560, 420));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            game.requestFocusInWindow();
        });
    }
}
