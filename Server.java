import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Study Circle SERVER (single file).
 *   java Server [port]             -> opens the server window
 *   java Server --console [port]   -> console only (also used automatically when there is no display)
 *
 * Wire protocol: one line per message, "TYPE|field|field|...", every field URL-encoded.
 */
public final class Server {

    // ================================================================ protocol

    static final int DEFAULT_PORT = 5000;

    // Client -> Server
    static final String JOIN = "JOIN", MSG = "MSG", NAME = "NAME", EXIT = "EXIT", HELP = "HELP";
    static final String ASK = "ASK", UPVOTE = "UPVOTE", ANSWER = "ANSWER";
    static final String POLL_NEW = "POLL_NEW", VOTE = "VOTE", POLL_CLOSE = "POLL_CLOSE";
    // Server -> Client
    static final String WELCOME = "WELCOME", ERR = "ERR", CHAT = "CHAT", SYS = "SYS";
    static final String USERS = "USERS", QUEUE = "QUEUE", POLLS = "POLLS", BYE = "BYE";

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    /** Builds one protocol line. */
    static String build(String type, String... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (String f : fields) sb.append('|').append(enc(f));
        return sb.toString();
    }

    /** Parses a line into [type, field1, field2, ...] (fields decoded). */
    static String[] parse(String line) {
        String[] raw = line.split("\\|", -1);
        String[] out = new String[raw.length];
        out[0] = raw[0];
        for (int i = 1; i < raw.length; i++) out[i] = dec(raw[i]);
        return out;
    }

    /** Joins sub-fields of a composite item with commas (each part encoded). */
    static String joinFields(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(enc(parts[i]));
        }
        return sb.toString();
    }

    // ================================================================ logging

    private static volatile Consumer<String> logSink;

    /** Always prints to the console, and also forwards to the server window if one is attached. */
    static void log(String text) {
        System.out.println(text);
        Consumer<String> s = logSink;
        if (s != null) s.accept(text);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static String now() { return LocalTime.now().format(TIME); }

    // ================================================================ server core

    private final Room room = new Room();
    private final ExecutorService pool = Executors.newCachedThreadPool(); // one thread per client
    private final Set<Handler> handlers = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean stopped;

    void setUsersListener(Consumer<List<String>> l) { room.usersListener = l; }

    /** Opens the port and starts accepting clients on a background thread. */
    void start(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        new Thread(this::acceptLoop, "accept").start();
    }

    private void acceptLoop() {
        ServerSocket server = serverSocket;
        while (!stopped) {
            try {
                Socket socket = server.accept();
                try {
                    Handler h = new Handler(socket, room);
                    handlers.add(h);
                    pool.execute(() -> {
                        try { h.run(); } finally { handlers.remove(h); }
                    });
                } catch (IOException e) {
                    log("Could not start handler: " + e.getMessage());
                    try { socket.close(); } catch (IOException ignored) { }
                }
            } catch (IOException e) {
                if (!stopped) log("Server error: " + e.getMessage());
                return;
            }
        }
    }

    /** Stops accepting, disconnects everyone and releases the port. */
    void stop() {
        stopped = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (Handler h : new ArrayList<>(handlers)) h.close();
        pool.shutdown();
    }

    static List<String> lanAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        String adapter = ni.getDisplayName();
                        // Hide the unwanted Qualcomm Atheros adapter/address from the server list.
                        if ("192.168.31.248".equals(ip)
                                || adapter.toLowerCase().contains("qualcomm atheros ar5bwb222")) {
                            continue;
                        }
                        result.add(ip + "  (" + adapter + ")");
                    }
                }
            }
        } catch (SocketException ignored) { }
        return result;
    }

    static String friendly(IOException e, int port) {
        if (e instanceof BindException)
            return "Port " + port + " is already in use. Close the other server or choose a different port.";
        return "Server error: " + e.getMessage();
    }

    public static void main(String[] args) {
        boolean console = GraphicsEnvironment.isHeadless();
        Integer port = null;
        for (String a : args) {
            if (a.equals("--console") || a.equals("--nogui")) { console = true; continue; }
            try {
                int p = Integer.parseInt(a);
                if (p < 1 || p > 65535) throw new NumberFormatException();
                port = p;
            } catch (NumberFormatException e) {
                System.err.println("Invalid port. Use a number from 1 to 65535.");
                return;
            }
        }
        final int startPort = port == null ? DEFAULT_PORT : port;

        if (!console) {
            SwingUtilities.invokeLater(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
                new ServerWindow(startPort).setVisible(true);
            });
            return;
        }

        Server server = new Server();
        try {
            server.start(startPort);
        } catch (IOException e) {
            System.err.println(friendly(e, startPort));
            return;
        }
        System.out.println("Study Circle server running on port " + startPort);
        for (String a : lanAddresses()) System.out.println("  Clients on the LAN connect to: " + a);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ================================================================ shared room state

    /** One entry in the shared question queue. */
    private static final class Question {
        final int id;
        final String asker;
        final String text;
        final Set<Integer> upvoters = new HashSet<>();
        boolean answered;

        Question(int id, String asker, String text, int askerHandlerId) {
            this.id = id;
            this.asker = asker;
            this.text = text;
            this.upvoters.add(askerHandlerId); // asking counts as the first vote
        }

        int votes() { return upvoters.size(); }
    }

    /** A live group poll. One vote per connected client. */
    private static final class Poll {
        final int id;
        final String question;
        final String creatorName;
        final Handler creator;
        final List<String> options;
        final int[] counts;
        final Map<Integer, Integer> votesByClient = new HashMap<>(); // handlerId -> option index
        boolean open = true;

        Poll(int id, String question, List<String> options, Handler creator, String creatorName) {
            this.id = id;
            this.question = question;
            this.options = List.copyOf(options);
            this.counts = new int[options.size()];
            this.creator = creator;
            this.creatorName = creatorName;
        }
    }

    /**
     * All shared state (users, question queue, polls). Every operation is synchronized, so
     * concurrent client threads can never corrupt the data or interleave broadcasts.
     * Methods return an error message when validation fails, or null on success.
     */
    private static final class Room {
        private static final int MAX_NICK = 20, MAX_TEXT = 500, MAX_QUESTIONS = 200, MAX_POLLS = 50;

        private final Map<Handler, String> members = new LinkedHashMap<>();
        private final List<Question> questions = new ArrayList<>();
        private final List<Poll> polls = new ArrayList<>();
        volatile Consumer<List<String>> usersListener = names -> { };
        private int nextQuestionId = 1;
        private int nextPollId = 1;

        // ---- users

        synchronized String join(Handler h, String raw) {
            if (members.containsKey(h)) return "You have already joined.";
            String nick = raw.trim();
            String err = checkNick(h, nick);
            if (err != null) return err;
            members.put(h, nick);
            h.send(WELCOME, nick);
            announce(nick + " joined the study circle.");
            broadcastUsers();
            sendQueue(h);
            sendPolls(h);
            return null;
        }

        synchronized String rename(Handler h, String raw) {
            String old = members.get(h);
            if (old == null) return "Join first.";
            String nick = raw.trim();
            if (nick.equals(old)) return "That is already your nickname.";
            String err = checkNick(h, nick);
            if (err != null) return err;
            members.put(h, nick);
            h.send(WELCOME, nick);
            announce(old + " is now known as " + nick + ".");
            broadcastUsers();
            return null;
        }

        synchronized void leave(Handler h) {
            String nick = members.remove(h);
            if (nick != null) {
                announce(nick + " left the study circle.");
                broadcastUsers();
                broadcastPolls(); // "canClose" may change if a creator left
            }
        }

        private String checkNick(Handler h, String nick) {
            if (nick.isEmpty()) return "Nickname cannot be blank.";
            if (nick.length() > MAX_NICK) return "Nickname must be at most " + MAX_NICK + " characters.";
            if (!nick.matches("[A-Za-z0-9_.-]+"))
                return "Nicknames may only contain letters, numbers, '_', '-' and '.' (no spaces).";
            for (Map.Entry<Handler, String> e : members.entrySet()) {
                if (e.getKey() != h && e.getValue().equalsIgnoreCase(nick))
                    return "The nickname \"" + nick + "\" is already in use.";
            }
            return null;
        }

        // ---- chat

        synchronized String chat(Handler h, String raw) {
            String nick = members.get(h);
            if (nick == null) return "Join first.";
            String text = raw.trim();
            if (text.isEmpty()) return "Cannot send a blank message.";
            if (text.length() > MAX_TEXT) return "Message too long (max " + MAX_TEXT + " characters).";
            broadcast(CHAT, now(), nick, text);
            return null;
        }

        // ---- feature 1: question queue

        synchronized String ask(Handler h, String raw) {
            String nick = members.get(h);
            if (nick == null) return "Join first.";
            String text = raw.trim();
            if (text.isEmpty()) return "A question cannot be blank.";
            if (text.length() > MAX_TEXT) return "Question too long (max " + MAX_TEXT + " characters).";
            if (questions.size() >= MAX_QUESTIONS) return "The question queue is full.";
            Question q = new Question(nextQuestionId++, nick, text, h.id);
            questions.add(q);
            announce(nick + " added question #" + q.id + " to the queue.");
            broadcastQueue();
            return null;
        }

        synchronized String upvote(Handler h, String idText) {
            if (!members.containsKey(h)) return "Join first.";
            Question q = findQuestion(idText);
            if (q == null) return "That question no longer exists.";
            if (q.answered) return "That question is already answered.";
            if (!q.upvoters.add(h.id)) return "You already upvoted that question.";
            broadcastQueue();
            return null;
        }

        synchronized String answer(Handler h, String idText) {
            String nick = members.get(h);
            if (nick == null) return "Join first.";
            Question q = findQuestion(idText);
            if (q == null) return "That question no longer exists.";
            if (q.answered) return "That question is already answered.";
            q.answered = true;
            announce(nick + " marked question #" + q.id + " as answered.");
            broadcastQueue();
            return null;
        }

        private Question findQuestion(String idText) {
            int id = Integer.parseInt(idText.trim());
            for (Question q : questions) if (q.id == id) return q;
            return null;
        }

        // ---- feature 2: group poll

        synchronized String newPoll(Handler h, String rawQuestion, List<String> rawOptions) {
            String nick = members.get(h);
            if (nick == null) return "Join first.";
            String question = rawQuestion.trim();
            if (question.isEmpty()) return "A poll needs a question.";
            if (question.length() > 200) return "Poll question too long (max 200 characters).";
            List<String> options = new ArrayList<>();
            for (String o : rawOptions) {
                String t = o.trim();
                if (t.isEmpty()) continue;
                if (t.length() > 60) return "Poll options must be at most 60 characters.";
                for (String existing : options)
                    if (existing.equalsIgnoreCase(t)) return "Poll options must be different from each other.";
                options.add(t);
            }
            if (options.size() < 2 || options.size() > 6) return "A poll needs between 2 and 6 options.";
            if (polls.size() >= MAX_POLLS) return "Too many polls in this session.";
            Poll p = new Poll(nextPollId++, question, options, h, nick);
            polls.add(p);
            announce(nick + " started poll #" + p.id + ": " + question);
            broadcastPolls();
            return null;
        }

        synchronized String vote(Handler h, String pollIdText, String optionText) {
            if (!members.containsKey(h)) return "Join first.";
            Poll p = findPoll(pollIdText);
            if (p == null) return "That poll no longer exists.";
            if (!p.open) return "That poll is closed.";
            int idx = Integer.parseInt(optionText.trim());
            if (idx < 0 || idx >= p.options.size()) return "Invalid poll option.";
            if (p.votesByClient.containsKey(h.id)) return "You already voted in this poll.";
            p.votesByClient.put(h.id, idx);
            p.counts[idx]++;
            broadcastPolls();
            return null;
        }

        synchronized String closePoll(Handler h, String pollIdText) {
            String nick = members.get(h);
            if (nick == null) return "Join first.";
            Poll p = findPoll(pollIdText);
            if (p == null) return "That poll no longer exists.";
            if (!p.open) return "That poll is already closed.";
            if (!canClose(h, p)) return "Only the poll creator can close this poll.";
            p.open = false;
            announce(nick + " closed poll #" + p.id + ".");
            broadcastPolls();
            return null;
        }

        private Poll findPoll(String idText) {
            int id = Integer.parseInt(idText.trim());
            for (Poll p : polls) if (p.id == id) return p;
            return null;
        }

        /** Creator can close; if the creator already left, any member can. */
        private boolean canClose(Handler h, Poll p) {
            return p.open && (p.creator == h || !members.containsKey(p.creator));
        }

        // ---- broadcasting

        private void announce(String text) {
            log("[" + now() + "] " + text);
            broadcast(SYS, now(), text);
        }

        private void broadcast(String type, String... fields) {
            for (Handler h : members.keySet()) h.send(type, fields);
        }

        private void broadcastUsers() {
            broadcast(USERS, members.values().toArray(new String[0]));
            usersListener.accept(new ArrayList<>(members.values()));
        }

        private void broadcastQueue() {
            for (Handler h : members.keySet()) sendQueue(h);
        }

        private void broadcastPolls() {
            for (Handler h : members.keySet()) sendPolls(h);
        }

        /** Whole queue (open questions by votes first, answered last). Per-client: includes "you upvoted". */
        private void sendQueue(Handler h) {
            List<Question> sorted = new ArrayList<>(questions);
            sorted.sort(Comparator.comparing((Question q) -> q.answered)
                    .thenComparingInt(q -> -q.votes())
                    .thenComparingInt(q -> q.id));
            String[] items = new String[sorted.size()];
            for (int i = 0; i < items.length; i++) {
                Question q = sorted.get(i);
                items[i] = joinFields(String.valueOf(q.id), q.asker, q.text, String.valueOf(q.votes()),
                        String.valueOf(q.answered), String.valueOf(q.upvoters.contains(h.id)));
            }
            h.send(QUEUE, items);
        }

        /** Every poll. Per-client: includes "your vote" and whether you may close it. */
        private void sendPolls(Handler h) {
            String[] items = new String[polls.size()];
            for (int i = 0; i < items.length; i++) {
                Poll p = polls.get(i);
                List<String> parts = new ArrayList<>();
                parts.add(String.valueOf(p.id));
                parts.add(p.question);
                parts.add(String.valueOf(p.open));
                parts.add(members.getOrDefault(p.creator, p.creatorName));
                parts.add(String.valueOf(p.votesByClient.getOrDefault(h.id, -1)));
                parts.add(String.valueOf(canClose(h, p)));
                for (int o = 0; o < p.options.size(); o++) {
                    parts.add(p.options.get(o));
                    parts.add(String.valueOf(p.counts[o]));
                }
                items[i] = joinFields(parts.toArray(new String[0]));
            }
            h.send(POLLS, items);
        }
    }

    // ================================================================ one thread per client

    /**
     * One instance (and one thread) per connected client. Reads protocol lines, validates them
     * and delegates to the Room. send() is synchronized so two threads never mix output on one socket.
     */
    private static final class Handler implements Runnable {
        private static final AtomicInteger IDS = new AtomicInteger();
        private static final String[] HELP_LINES = {
            "Commands: /name <newname> (change nickname), /exit (leave), /help (this list).",
            "Question Queue tab: ask a question, upvote others, mark answered.",
            "Polls tab: start a poll (2-6 options), vote once, creator closes it."
        };

        final int id = IDS.incrementAndGet();
        private final Socket socket;
        private final Room room;
        private final BufferedReader in;
        private final PrintWriter out;
        private volatile boolean closed;
        private boolean joined;

        Handler(Socket socket, Room room) throws IOException {
            this.socket = socket;
            this.room = room;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            String who = String.valueOf(socket.getRemoteSocketAddress());
            log("[" + now() + "] Connection opened: " + who);
            try {
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    if (!handle(line)) break;
                }
            } catch (IOException e) {
                log("[" + now() + "] Connection lost: " + who + " (" + e.getMessage() + ")");
            } finally {
                room.leave(this);   // frees the nickname and tells everyone else
                close();
                log("[" + now() + "] Connection closed: " + who);
            }
        }

        /** @return false when this client should be disconnected. */
        private boolean handle(String line) {
            if (line.isBlank()) return true;
            if (line.length() > 4000) { send(ERR, "Message too long."); return true; }
            String[] f;
            try {
                f = parse(line);
            } catch (IllegalArgumentException e) {
                send(ERR, "Malformed message.");
                return true;
            }
            String type = f[0];
            if (!joined && !type.equals(JOIN) && !type.equals(EXIT)) {
                send(ERR, "Please join with a nickname first.");
                return true;
            }
            String err = null;
            try {
                switch (type) {
                    case JOIN -> {
                        err = room.join(this, arg(f, 1));
                        if (err == null) joined = true;
                    }
                    case MSG -> err = room.chat(this, arg(f, 1));
                    case NAME -> err = room.rename(this, arg(f, 1));
                    case HELP -> { for (String h : HELP_LINES) send(SYS, now(), h); }
                    case ASK -> err = room.ask(this, arg(f, 1));
                    case UPVOTE -> err = room.upvote(this, arg(f, 1));
                    case ANSWER -> err = room.answer(this, arg(f, 1));
                    case POLL_NEW -> {
                        List<String> opts = f.length > 2 ? Arrays.asList(f).subList(2, f.length) : Collections.emptyList();
                        err = room.newPoll(this, arg(f, 1), opts);
                    }
                    case VOTE -> err = room.vote(this, arg(f, 1), arg(f, 2));
                    case POLL_CLOSE -> err = room.closePoll(this, arg(f, 1));
                    case EXIT -> { send(BYE); return false; }
                    default -> err = "Unknown command: " + type;
                }
            } catch (NumberFormatException e) {
                err = "Invalid number in request.";
            }
            if (err != null) send(ERR, err);
            return true;
        }

        private static String arg(String[] f, int i) { return i < f.length ? f[i] : ""; }

        synchronized void send(String type, String... fields) {
            if (closed) return;
            out.println(build(type, fields));
            out.flush();
            if (out.checkError()) close();
        }

        void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // ================================================================ server window

    private static final class ServerWindow extends JFrame {
        private static final Color BG = new Color(10, 18, 34);
        private static final Color PANEL = new Color(18, 31, 52);
        private static final Color NAVY = new Color(12, 27, 49);
        private static final Color INPUT = new Color(9, 22, 40);
        private static final Color BLUE = new Color(35, 115, 205);
        private static final Color GREEN = new Color(20, 155, 105);
        private static final Color RED = new Color(210, 75, 75);
        private static final Color TEXT = new Color(230, 238, 248);
        private static final Color MUTED = new Color(151, 170, 194);
        private static final Color BORDER = new Color(43, 68, 96);
        private static final int MAX_LOG_CHARS = 200_000;

        private final JTextField portField;
        private final JButton toggleButton = new JButton("Stop server");
        private final JLabel statusLabel = new JLabel(" Server is stopped.");
        private final JTextArea logArea = new JTextArea();
        private final DefaultListModel<String> usersModel = new DefaultListModel<>();
        private final JLabel usersTitle = new JLabel("Online (0)");
        private final JTextArea addressArea = new JTextArea(4, 20);
        private Server server;   // null while stopped (EDT only)

        ServerWindow(int port) {
            super("Study Circle - Server");
            logSink = value -> SwingUtilities.invokeLater(() -> appendLog(value));
            portField = new JTextField(String.valueOf(port), 6);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    stopServer();
                    logSink = null;
                    dispose();
                }
            });

            getContentPane().setBackground(BG);
            setLayout(new BorderLayout(10, 10));
            ((JComponent) getContentPane()).setBorder(new EmptyBorder(12, 12, 10, 12));
            add(buildTopBar(), BorderLayout.NORTH);
            add(buildCenter(), BorderLayout.CENTER);
            statusLabel.setOpaque(true);
            statusLabel.setBackground(NAVY);
            statusLabel.setBorder(new EmptyBorder(9, 10, 9, 10));
            statusLabel.setForeground(RED);
            add(statusLabel, BorderLayout.SOUTH);

            setSize(900, 600);
            setMinimumSize(new Dimension(700, 470));
            setLocationRelativeTo(null);
            refreshAddresses();
            startServer(port);
        }

        // ---- start / stop

        private void startServer(int port) {
            if (server != null) return;
            Server candidate = new Server();
            candidate.setUsersListener(names -> SwingUtilities.invokeLater(() -> showUsers(names)));
            try {
                candidate.start(port);
                server = candidate;
                showRunning(true, "Server is running on port " + port + ".", false);
                log("Study Circle server running on port " + port);
                for (String address : lanAddresses()) log("LAN address: " + address);
            } catch (IOException ex) {
                String message = friendly(ex, port);
                showRunning(false, message, true);
                JOptionPane.showMessageDialog(this, message, "Study Circle", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void stopServer() {
            if (server == null) return;
            server.stop();
            server = null;
            showRunning(false, "Server is stopped.", false);
            log("Server stopped.");
        }

        private void showRunning(boolean running, String message, boolean error) {
            portField.setEnabled(!running);
            toggleButton.setText(running ? "Stop server" : "Start server");
            toggleButton.setBackground(running ? RED : GREEN);
            statusLabel.setForeground(error ? new Color(255, 130, 130) : new Color(80, 220, 155));
            statusLabel.setText(" " + message);
            if (running) refreshAddresses();
            else showUsers(List.of());
        }

        // ---- building the UI

        private JPanel buildTopBar() {
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
            bar.setBackground(NAVY);
            bar.setBorder(new CompoundBorder(new LineBorder(BORDER), new EmptyBorder(3, 5, 3, 5)));

            JLabel title = new JLabel("STUDY CIRCLE SERVER");
            title.setForeground(TEXT);
            title.setFont(new Font("Segoe UI", Font.BOLD, 18));
            JLabel port = new JLabel("PORT");
            port.setForeground(MUTED);
            port.setFont(new Font("Segoe UI", Font.BOLD, 10));
            styleField(portField);
            portField.setPreferredSize(new Dimension(85, 34));
            styleButton(toggleButton, RED);

            bar.add(title);
            bar.add(Box.createHorizontalStrut(15));
            bar.add(port);
            bar.add(portField);
            bar.add(toggleButton);
            toggleButton.addActionListener(e -> {
                if (server != null) {
                    stopServer();
                } else {
                    int p = readPort();
                    if (p > 0) startServer(p);
                }
            });
            portField.addActionListener(e -> toggleButton.doClick());
            return bar;
        }

        private JSplitPane buildCenter() {
            logArea.setEditable(false);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            logArea.setBackground(INPUT);
            logArea.setForeground(TEXT);
            logArea.setCaretColor(TEXT);
            logArea.setBorder(new EmptyBorder(10, 10, 10, 10));
            ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

            JButton clear = new JButton("Clear log");
            styleButton(clear, BLUE);
            clear.addActionListener(e -> logArea.setText(""));

            JPanel logPanel = createPanel("SERVER ACTIVITY");
            JPanel logTop = new JPanel(new BorderLayout());
            logTop.setOpaque(false);
            logTop.add(sectionLabel("Live server messages"), BorderLayout.WEST);
            logTop.add(clear, BorderLayout.EAST);
            logPanel.add(logTop, BorderLayout.NORTH);
            logPanel.add(styleScroll(new JScrollPane(logArea)), BorderLayout.CENTER);

            addressArea.setEditable(false);
            addressArea.setFocusable(false);
            addressArea.setLineWrap(true);
            addressArea.setWrapStyleWord(true);
            addressArea.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            addressArea.setBackground(INPUT);
            addressArea.setForeground(TEXT);
            addressArea.setBorder(new EmptyBorder(8, 8, 8, 8));

            JPanel side = new JPanel(new BorderLayout(0, 10));
            side.setOpaque(false);
            side.setBorder(new EmptyBorder(0, 0, 0, 0));

            JPanel addressPanel = createPanel("LAN CONNECTION");
            addressPanel.add(sectionLabel("Clients can connect using:"), BorderLayout.NORTH);
            addressPanel.add(styleScroll(new JScrollPane(addressArea)), BorderLayout.CENTER);
            addressPanel.setPreferredSize(new Dimension(270, 170));
            side.add(addressPanel, BorderLayout.NORTH);

            JPanel users = createPanel("ONLINE MEMBERS");
            usersTitle.setForeground(TEXT);
            usersTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
            users.add(usersTitle, BorderLayout.NORTH);
            JList<String> userList = new JList<>(usersModel);
            userList.setBackground(INPUT);
            userList.setForeground(TEXT);
            userList.setSelectionBackground(BLUE);
            userList.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            users.add(styleScroll(new JScrollPane(userList)), BorderLayout.CENTER);
            side.add(users, BorderLayout.CENTER);
            side.setPreferredSize(new Dimension(275, 0));

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, logPanel, side);
            split.setResizeWeight(0.70);
            split.setDividerLocation(0.70);
            split.setDividerSize(7);
            split.setBorder(null);
            split.setBackground(BG);
            split.setOpaque(false);
            return split;
        }

        private JPanel createPanel(String title) {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBackground(PANEL);
            panel.setBorder(new CompoundBorder(new LineBorder(BORDER), new EmptyBorder(11, 11, 11, 11)));
            JLabel heading = new JLabel(title);
            heading.setForeground(TEXT);
            heading.setFont(new Font("Segoe UI", Font.BOLD, 13));
            panel.add(heading, BorderLayout.WEST);
            return panel;
        }

        private JLabel sectionLabel(String value) {
            JLabel label = new JLabel(value);
            label.setForeground(MUTED);
            label.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            return label;
        }

        private void styleField(JTextField field) {
            field.setBackground(INPUT);
            field.setForeground(TEXT);
            field.setCaretColor(TEXT);
            field.setBorder(new CompoundBorder(new LineBorder(BORDER), new EmptyBorder(6, 8, 6, 8)));
            field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        }

        private void styleButton(JButton button, Color color) {
            button.setBackground(color);
            button.setForeground(Color.WHITE);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setFocusPainted(false);
            button.setBorderPainted(false);
            button.setBorder(new EmptyBorder(8, 13, 8, 13));
            button.setFont(new Font("Segoe UI", Font.BOLD, 11));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private JScrollPane styleScroll(JScrollPane scroll) {
            scroll.setBorder(new LineBorder(BORDER));
            scroll.getViewport().setBackground(INPUT);
            scroll.setBackground(PANEL);
            return scroll;
        }

        private void refreshAddresses() {
            List<String> addrs = lanAddresses();
            addressArea.setText(addrs.isEmpty()
                    ? "localhost (no other LAN address found)"
                    : String.join("\n", addrs));
            addressArea.setRows(Math.max(2, addrs.size()));
            addressArea.setCaretPosition(0);
        }

        /** @return the port typed in the box, or -1 (after showing a message) when it is invalid. */
        private int readPort() {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
                return port;
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "The port must be a number from 1 to 65535.",
                        "Study Circle", JOptionPane.ERROR_MESSAGE);
                return -1;
            }
        }

        private void showUsers(List<String> names) {
            usersModel.clear();
            for (String name : names) usersModel.addElement(name);
            usersTitle.setText("Online (" + names.size() + ")");
        }

        private void appendLog(String value) {
            logArea.append(value + "\n");
            int excess = logArea.getDocument().getLength() - MAX_LOG_CHARS;
            if (excess > 0) logArea.replaceRange("", 0, excess);
        }
    }
}
