import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Study Circle CLIENT (single file):  java Client
 * Swing window with three tabs: Chat | Question Queue | Group Polls.
 * Sockets are handled on background threads; every Swing update runs on the Event Dispatch Thread.
 */
public final class Client extends JFrame {

    // ================================================================ protocol (same as Server.java)

    private static final int DEFAULT_PORT = 5000;

    private static final String JOIN = "JOIN", MSG = "MSG", NAME = "NAME", EXIT = "EXIT", HELP = "HELP";
    private static final String ASK = "ASK", UPVOTE = "UPVOTE", ANSWER = "ANSWER";
    private static final String POLL_NEW = "POLL_NEW", VOTE = "VOTE", POLL_CLOSE = "POLL_CLOSE";
    private static final String WELCOME = "WELCOME", ERR = "ERR", CHAT = "CHAT", SYS = "SYS";
    private static final String USERS = "USERS", QUEUE = "QUEUE", POLLS = "POLLS", BYE = "BYE";

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static String build(String type, String... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (String f : fields) sb.append('|').append(enc(f));
        return sb.toString();
    }

    private static String[] parse(String line) {
        String[] raw = line.split("\\|", -1);
        String[] out = new String[raw.length];
        out[0] = raw[0];
        for (int i = 1; i < raw.length; i++) out[i] = dec(raw[i]);
        return out;
    }

    private static String[] split(String item) {
        String[] raw = item.split(",", -1);
        for (int i = 0; i < raw.length; i++) raw[i] = dec(raw[i]);
        return raw;
    }

    // ================================================================ data received from the server

    /** One question in the shared queue. */
    private record QuestionItem(int id, String asker, String text, int votes, boolean answered, boolean upvotedByMe) { }

    /** One poll with live counts. myVote = -1 when you have not voted. */
    private record PollItem(int id, String question, boolean open, String creator, int myVote,
                            boolean canClose, List<String> options, List<Integer> counts) {
        int total() {
            int sum = 0;
            for (int c : counts) sum += c;
            return sum;
        }
    }

    // ================================================================ network connection

    /**
     * Owns the Socket. A reader thread receives lines and hands them to a callback; a single-thread
     * executor sends lines, so the GUI thread never blocks on the network.
     */
    private static final class Connection {
        private Socket socket;
        private PrintWriter out;
        private volatile boolean closed;
        private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sender");
            t.setDaemon(true);
            return t;
        });

        /** Blocking connect (call from a background thread). Starts the reader thread on success. */
        void connect(String host, int port, Consumer<String> onLine, Consumer<String> onClosed) throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 4000);
            socket = s;
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                String reason = "the server closed the connection";
                try {
                    String line;
                    while ((line = in.readLine()) != null) onLine.accept(line);
                } catch (IOException e) {
                    reason = closed ? "disconnected" : (e.getMessage() == null ? "network error" : e.getMessage());
                } finally {
                    closeSocket();
                    onClosed.accept(reason);
                }
            }, "reader");
            reader.setDaemon(true);
            reader.start();
        }

        boolean isOpen() {
            return socket != null && !closed && !socket.isClosed();
        }

        /** Queues a line for sending (never blocks the caller). */
        void send(String line) {
            try {
                sender.execute(() -> {
                    if (out == null) return;
                    out.println(line);
                    out.flush();
                    if (out.checkError()) closeSocket();
                });
            } catch (RejectedExecutionException ignored) { }
        }

        /** Sends anything already queued, then closes the socket and releases resources. */
        void close() {
            closed = true;
            try {
                sender.execute(this::closeSocket);
            } catch (RejectedExecutionException ignored) { }
            sender.shutdown();
        }

        /** Like close(), but waits briefly so a queued EXIT is really sent before the JVM exits. */
        void closeAndWait() {
            close();
            try {
                sender.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void closeSocket() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) { }
        }
    }

    // ================================================================ colours

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color NAVY = new Color(15, 16, 34);
    private static final Color PANEL = new Color(29, 31, 46);
    private static final Color INPUT = new Color(27, 29, 44);
    private static final Color BORDER = new Color(58, 61, 79);
    private static final Color BLUE = new Color(103, 96, 235);
    private static final Color SECTION_BLUE = new Color(34, 36, 53);
    private static final Color SECTION_BLUE_DARK = new Color(20, 22, 38);
    private static final Color GREEN = new Color(13, 153, 143);
    private static final Color RED = new Color(213, 83, 91);
    private static final Color PURPLE = new Color(67, 57, 165);
    private static final Color SLATE = new Color(61, 67, 87);
    private static final Color MUTED = new Color(157, 163, 184);
    private static final Color TEXT = new Color(239, 241, 250);

    // ================================================================ state

    private Connection conn;          // current connection (EDT only)
    private boolean connecting;
    private boolean joined;           // server accepted our nickname
    private boolean connected;        // same as joined, as shown in the UI
    private boolean exiting;          // true when the user chose to leave
    private String nickname = "";

    // connection bar
    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 5);
    private final JTextField nickField = new JTextField(10);
    private final JButton connectButton = new JButton("Connect");
    private final JLabel statusLabel = new JLabel(" Not connected.");

    // chat tab
    private final JTextPane transcript = new JTextPane();
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JLabel usersTitle = new JLabel("Online (0)");
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Send");

    // question queue tab
    private final DefaultTableModel queueTableModel = new DefaultTableModel(
            new Object[]{"#", "Question", "Asked by", "Votes", "You", "Status"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable queueTable = new JTable(queueTableModel);
    private final JTextField questionField = new JTextField();
    private final JButton askButton = new JButton("Ask");
    private final JButton upvoteButton = new JButton("▲ Upvote selected");
    private final JButton answerButton = new JButton("✓ Mark answered");
    private List<QuestionItem> currentQuestions = new ArrayList<>();

    // polls tab
    private final JPanel pollsPanel = new JPanel();
    private final JButton newPollButton = new JButton("+ New poll");

    // ================================================================ entry point

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            new Client().setVisible(true);
        });
    }

    private Client() {
        super("Study Circle - LAN Study Room");

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onWindowClosing(); }
        });

        getContentPane().setBackground(NAVY);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background", SLATE);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Table.background", INPUT);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", new Color(69, 63, 133));
        UIManager.put("Table.selectionForeground", TEXT);
        setLayout(new BorderLayout(0, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        add(buildConnectionBar(), BorderLayout.NORTH);

        // Force a dark Swing tab delegate instead of relying on the operating-system
        // Look & Feel, which may paint tab backgrounds white and make labels unreadable.
        UIManager.put("TabbedPane.background", PANEL);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", SECTION_BLUE);
        UIManager.put("TabbedPane.contentAreaColor", PANEL);
        UIManager.put("TabbedPane.focus", BORDER);
        UIManager.put("TabbedPane.borderHightlightColor", BORDER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setUI(new BasicTabbedPaneUI() {
            @Override
            protected void paintTabBackground(Graphics g, int placement, int index,
                    int x, int y, int w, int h, boolean selected) {
                g.setColor(selected ? SECTION_BLUE : SECTION_BLUE_DARK);
                g.fillRect(x, y, w, h);
            }
            @Override
            protected void paintTabBorder(Graphics g, int placement, int index,
                    int x, int y, int w, int h, boolean selected) {
                g.setColor(BORDER);
                g.drawRect(x, y, w, h);
            }
            @Override
            protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
                g.setColor(BORDER);
                int left = tabPane.getInsets().left;
                int top = tabPane.getInsets().top + calculateTabAreaHeight(placement, runCount, maxTabHeight);
                g.drawRect(left, top, tabPane.getWidth() - left - tabPane.getInsets().right - 1,
                        tabPane.getHeight() - top - tabPane.getInsets().bottom - 1);
            }
        });
        tabs.setBackground(PANEL);
        tabs.setForeground(TEXT);
        tabs.setOpaque(true);
        tabs.setFont(new Font("Segoe UI", Font.BOLD, 12));
        tabs.setFocusable(false);
        tabs.addTab("💬 Chat", buildChatTab());
        tabs.addTab("❓ Question Queue", buildQueueTab());
        tabs.addTab("📊 Group Polls", buildPollsTab());
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setBackgroundAt(i, i == tabs.getSelectedIndex() ? SECTION_BLUE : SECTION_BLUE_DARK);
            tabs.setForegroundAt(i, TEXT);
        }
        tabs.addChangeListener(e -> {
            for (int i = 0; i < tabs.getTabCount(); i++)
                tabs.setBackgroundAt(i, i == tabs.getSelectedIndex() ? SECTION_BLUE : SECTION_BLUE_DARK);
        });
        add(tabs, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        statusLabel.setBackground(PANEL);
        statusLabel.setOpaque(true);
        statusLabel.setForeground(new Color(145, 160, 180));
        add(statusLabel, BorderLayout.SOUTH);

        setSize(980, 680);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);
        connectionChanged(false, "");
        renderPolls(new ArrayList<>());
    }

    // ================================================================ building the UI

    private JPanel buildConnectionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel brand = whiteLabel("STUDY CIRCLE");
        brand.setFont(new Font("Segoe UI", Font.BOLD, 18));
        bar.add(brand);
        bar.add(whiteLabel("Server IP"));
        bar.add(hostField);
        bar.add(whiteLabel("Port"));
        bar.add(portField);
        bar.add(whiteLabel("Nickname"));
        bar.add(nickField);
        bar.add(connectButton);
        styleField(hostField);
        styleField(portField);
        styleField(nickField);
        styleButton(connectButton, GREEN);
        connectButton.addActionListener(e -> {
            if (connected) onDisconnect();
            else onConnect(hostField.getText(), portField.getText(), nickField.getText());
        });
        nickField.addActionListener(e -> connectButton.doClick());
        return bar;
    }

    private void styleField(JTextField field) {
        field.setBackground(INPUT);
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setSelectionColor(new Color(38, 77, 119));
        field.setSelectedTextColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    }

    private void styleButton(JButton button, Color color) {
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private JLabel whiteLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(225, 235, 248));
        return l;
    }

    private JPanel buildChatTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        transcript.setEditable(false);
        transcript.setBackground(SECTION_BLUE_DARK);
        transcript.setForeground(Color.WHITE);
        transcript.setCaretColor(Color.WHITE);
        transcript.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane chatScroll = new JScrollPane(transcript);
        chatScroll.getViewport().setBackground(SECTION_BLUE_DARK);
        chatScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        panel.add(chatScroll, BorderLayout.CENTER);

        JList<String> usersList = new JList<>(usersModel);
        usersList.setBackground(SECTION_BLUE);
        usersList.setForeground(Color.WHITE);
        usersList.setSelectionBackground(new Color(38, 77, 119));
        usersList.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JPanel users = new JPanel(new BorderLayout(0, 4));
        usersTitle.setFont(usersTitle.getFont().deriveFont(Font.BOLD));
        users.add(usersTitle, BorderLayout.NORTH);
        users.add(new JScrollPane(usersList), BorderLayout.CENTER);
        users.setBackground(SECTION_BLUE);
        usersTitle.setForeground(TEXT);
        users.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        users.setPreferredSize(new Dimension(170, 0));
        panel.add(users, BorderLayout.EAST);

        JPanel input = new JPanel(new BorderLayout(8, 0));
        input.setBackground(SECTION_BLUE);
        input.add(inputField, BorderLayout.CENTER);
        input.add(sendButton, BorderLayout.EAST);
        panel.add(input, BorderLayout.SOUTH);

        styleField(inputField);
        styleButton(sendButton, PURPLE);
        Runnable send = () -> {
            String text = inputField.getText();
            onSend(text);
            if (!text.isBlank()) inputField.setText("");
        };
        sendButton.addActionListener(e -> send.run());
        inputField.addActionListener(e -> send.run());
        inputField.setToolTipText("Type a message, or /name <newname>, /exit, /help");
        return panel;
    }

    private JPanel buildQueueTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel queueTitle = whiteLabel("Shared question queue - most upvoted first:");
        top.setBackground(PANEL);
        top.add(queueTitle);
        top.add(upvoteButton);
        top.add(answerButton);
        panel.add(top, BorderLayout.NORTH);

        queueTable.setRowHeight(28);
        queueTable.setBackground(SECTION_BLUE_DARK);
        queueTable.setForeground(Color.WHITE);
        queueTable.setGridColor(BORDER);
        queueTable.setSelectionBackground(new Color(38, 77, 119));
        queueTable.setSelectionForeground(Color.WHITE);
        queueTable.setFillsViewportHeight(true);
        queueTable.getTableHeader().setBackground(SECTION_BLUE);
        queueTable.getTableHeader().setForeground(TEXT);
        queueTable.getTableHeader().setOpaque(true);
        queueTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        queueTable.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focused, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                        table, value, selected, focused, row, column);
                label.setOpaque(true);
                label.setBackground(SECTION_BLUE);
                label.setForeground(TEXT);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, BORDER),
                        BorderFactory.createEmptyBorder(5, 7, 5, 7)));
                return label;
            }
        });
        queueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queueTable.getColumnModel().getColumn(0).setMaxWidth(40);
        queueTable.getColumnModel().getColumn(1).setPreferredWidth(420);
        queueTable.getColumnModel().getColumn(3).setMaxWidth(60);
        queueTable.getColumnModel().getColumn(4).setMaxWidth(50);
        queueTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    c.setForeground("Answered".equals(t.getValueAt(row, 5)) ? MUTED : TEXT);
                    c.setBackground(INPUT);
                } else {
                    c.setForeground(TEXT);
                    c.setBackground(new Color(69, 63, 133));
                }
                return c;
            }
        });
        JScrollPane queueScroll = new JScrollPane(queueTable);
        queueScroll.getViewport().setBackground(SECTION_BLUE_DARK);
        queueScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        panel.add(queueScroll, BorderLayout.CENTER);

        JPanel ask = new JPanel(new BorderLayout(8, 0));
        ask.setBackground(PANEL);
        ask.add(whiteLabel("Your question:"), BorderLayout.WEST);
        ask.add(questionField, BorderLayout.CENTER);
        ask.add(askButton, BorderLayout.EAST);
        panel.add(ask, BorderLayout.SOUTH);

        styleField(questionField);
        styleButton(askButton, GREEN);
        styleButton(upvoteButton, BLUE);
        styleButton(answerButton, PURPLE);
        Runnable doAsk = () -> {
            String text = questionField.getText();
            onAsk(text);
            if (!text.isBlank()) questionField.setText("");
        };
        askButton.addActionListener(e -> doAsk.run());
        questionField.addActionListener(e -> doAsk.run());
        upvoteButton.addActionListener(e -> {
            int id = selectedQuestionId();
            if (id < 0) showNotice("Select a question first.", true); else onUpvote(id);
        });
        answerButton.addActionListener(e -> {
            int id = selectedQuestionId();
            if (id < 0) showNotice("Select a question first.", true); else onAnswer(id);
        });
        return panel;
    }

    private JPanel buildPollsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel pollsTitle = whiteLabel("Group polls - results update live for everyone:");
        top.setBackground(PANEL);
        top.add(pollsTitle);
        top.add(newPollButton);
        panel.add(top, BorderLayout.NORTH);

        pollsPanel.setBackground(SECTION_BLUE_DARK);
        pollsPanel.setLayout(new BoxLayout(pollsPanel, BoxLayout.Y_AXIS));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(SECTION_BLUE_DARK);
        wrapper.add(pollsPanel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(SECTION_BLUE_DARK);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        panel.add(scroll, BorderLayout.CENTER);

        styleButton(newPollButton, GREEN);
        newPollButton.addActionListener(e -> showNewPollDialog());
        return panel;
    }

    private void showNewPollDialog() {
        JTextField q = new JTextField(28);
        JTextField[] opts = new JTextField[4];
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.setBackground(PANEL);
        JLabel pollQuestionLabel = new JLabel("Poll question:");
        pollQuestionLabel.setForeground(TEXT);
        form.add(pollQuestionLabel);
        styleField(q);
        form.add(q);
        for (int i = 0; i < opts.length; i++) {
            opts[i] = new JTextField(28);
            JLabel optionLabel = new JLabel("Option " + (i + 1) + (i < 2 ? " (required)" : " (optional)") + ":");
            optionLabel.setForeground(TEXT);
            form.add(optionLabel);
            styleField(opts[i]);
            form.add(opts[i]);
        }
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        int result = JOptionPane.showConfirmDialog(this, form, "New poll", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        List<String> options = new ArrayList<>();
        for (JTextField f : opts) if (!f.getText().isBlank()) options.add(f.getText().trim());
        onNewPoll(q.getText(), options);
    }

    // ================================================================ user actions

    private void onConnect(String hostText, String portText, String nickText) {
        String host = hostText.trim();
        String nick = nickText.trim();
        if (host.isEmpty() || host.contains(" ")) {
            showError("Enter a valid server IP address (for example 192.168.1.10) or host name.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("The port must be a number from 1 to 65535.");
            return;
        }
        if (nick.isEmpty()) {
            showError("Nickname cannot be blank.");
            return;
        }
        if (connecting) return;
        if (conn != null && conn.isOpen()) {      // socket is open but the nickname was rejected: retry JOIN
            conn.send(build(JOIN, nick));
            return;
        }

        connecting = true;
        showNotice("Connecting to " + host + ":" + port + " ...", false);
        final Connection c = new Connection();
        conn = c;
        new Thread(() -> {
            try {
                c.connect(host, port,
                        line -> SwingUtilities.invokeLater(() -> onLine(c, line)),
                        reason -> SwingUtilities.invokeLater(() -> onClosed(c, reason)));
                c.send(build(JOIN, nick));
                SwingUtilities.invokeLater(() -> connecting = false);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    if (conn == c) conn = null;
                    c.close();
                    showNotice("Could not connect.", true);
                    showError("Could not connect to " + host + ":" + port + " - " + friendly(e));
                });
            }
        }, "connect").start();
    }

    private void onDisconnect() {
        if (conn == null) return;
        exiting = true;
        conn.send(build(EXIT));
        conn.close();
    }

    private void onSend(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            showNotice("Blank messages are not sent.", true);
            return;
        }
        if (!ensureJoined()) return;
        if (!t.startsWith("/")) {
            conn.send(build(MSG, t));
            return;
        }
        String[] parts = t.split("\\s+", 2);
        switch (parts[0].toLowerCase()) {
            case "/name" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    systemMessage(now(), "Usage: /name <newname>", true);
                } else {
                    conn.send(build(NAME, parts[1].trim()));
                }
            }
            case "/exit" -> onDisconnect();
            case "/help" -> conn.send(build(HELP));
            default -> systemMessage(now(), "Unknown command \"" + parts[0] + "\". Type /help.", true);
        }
    }

    private void onAsk(String text) {
        if (text.isBlank()) {
            showNotice("A question cannot be blank.", true);
            return;
        }
        if (ensureJoined()) conn.send(build(ASK, text.trim()));
    }

    private void onUpvote(int questionId) {
        if (ensureJoined()) conn.send(build(UPVOTE, String.valueOf(questionId)));
    }

    private void onAnswer(int questionId) {
        if (ensureJoined()) conn.send(build(ANSWER, String.valueOf(questionId)));
    }

    private void onNewPoll(String question, List<String> options) {
        if (question.isBlank()) {
            showNotice("A poll needs a question.", true);
            return;
        }
        if (options.size() < 2) {
            showNotice("A poll needs at least 2 options.", true);
            return;
        }
        if (!ensureJoined()) return;
        List<String> fields = new ArrayList<>();
        fields.add(question.trim());
        fields.addAll(options);
        conn.send(build(POLL_NEW, fields.toArray(new String[0])));
    }

    private void onVote(int pollId, int optionIndex) {
        if (ensureJoined()) conn.send(build(VOTE, String.valueOf(pollId), String.valueOf(optionIndex)));
    }

    private void onClosePoll(int pollId) {
        if (ensureJoined()) conn.send(build(POLL_CLOSE, String.valueOf(pollId)));
    }

    private void onWindowClosing() {
        if (conn != null) {
            exiting = true;
            conn.send(build(EXIT));
            conn.closeAndWait();
        }
        dispose();
        System.exit(0);
    }

    // ================================================================ server messages (EDT)

    private void onLine(Connection c, String line) {
        if (c != conn) return;
        String[] f;
        try {
            f = parse(line);
        } catch (IllegalArgumentException e) {
            return; // ignore malformed lines
        }
        try {
            switch (f[0]) {
                case WELCOME -> {
                    joined = true;
                    nickname = f[1];
                    connectionChanged(true, f[1]);
                }
                case ERR -> {
                    if (joined) {
                        systemMessage(now(), f[1], true);
                        showNotice(f[1], true);
                    } else {
                        showError(f[1]);          // e.g. duplicate nickname at join time
                    }
                }
                case CHAT -> chatReceived(f[1], f[2], f[3], f[2].equals(nickname));
                case SYS -> systemMessage(f[1], f[2], false);
                case USERS -> usersChanged(new ArrayList<>(List.of(f).subList(1, f.length)));
                case QUEUE -> questionsChanged(parseQueue(f));
                case POLLS -> renderPolls(parsePolls(f));
                case BYE -> { /* the socket closes next; onClosed handles the cleanup */ }
                default -> { }
            }
        } catch (RuntimeException e) {
            // A malformed server message must never crash the GUI.
            System.err.println("Ignoring bad message: " + line);
        }
    }

    private void onClosed(Connection c, String reason) {
        if (c != conn) return;
        conn = null;
        boolean wasJoined = joined;
        joined = false;
        nickname = "";
        connectionChanged(false, "");
        if (exiting) {
            systemMessage(now(), "You left the study circle.", false);
        } else if (wasJoined) {
            systemMessage(now(), "Connection lost: " + reason + ". You can reconnect from the top bar.", true);
            showError("Connection to the server was lost (" + reason + ").");
        } else {
            showNotice("Disconnected.", true);
        }
        exiting = false;
    }

    private static List<QuestionItem> parseQueue(String[] f) {
        List<QuestionItem> list = new ArrayList<>();
        for (int i = 1; i < f.length; i++) {
            String[] q = split(f[i]);
            list.add(new QuestionItem(Integer.parseInt(q[0]), q[1], q[2], Integer.parseInt(q[3]),
                    Boolean.parseBoolean(q[4]), Boolean.parseBoolean(q[5])));
        }
        return list;
    }

    private static List<PollItem> parsePolls(String[] f) {
        List<PollItem> list = new ArrayList<>();
        for (int i = 1; i < f.length; i++) {
            String[] p = split(f[i]);
            List<String> options = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (int k = 6; k + 1 < p.length; k += 2) {
                options.add(p[k]);
                counts.add(Integer.parseInt(p[k + 1]));
            }
            list.add(new PollItem(Integer.parseInt(p[0]), p[1], Boolean.parseBoolean(p[2]), p[3],
                    Integer.parseInt(p[4]), Boolean.parseBoolean(p[5]), options, counts));
        }
        return list;
    }

    private boolean ensureJoined() {
        if (conn == null || !conn.isOpen() || !joined) {
            showNotice("You are not connected. Use the Connect button first.", true);
            return false;
        }
        return true;
    }

    private static String now() {
        return LocalTime.now().format(TIME);
    }

    private static String friendly(IOException e) {
        if (e instanceof ConnectException) return "connection refused. Is the server running, and are the IP and port correct?";
        if (e instanceof SocketTimeoutException) return "the server did not answer in time. Check the IP address and your Wi-Fi.";
        if (e instanceof UnknownHostException) return "unknown host. Check the IP address.";
        return e.getMessage() == null ? "network error." : e.getMessage();
    }

    // ================================================================ updating the screen (EDT)

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Study Circle", JOptionPane.ERROR_MESSAGE);
    }

    private void showNotice(String message, boolean error) {
        statusLabel.setForeground(error ? RED : GREEN);
        statusLabel.setText(" " + message);
    }

    private void connectionChanged(boolean isConnected, String nick) {
        connected = isConnected;
        connectButton.setText(isConnected ? "Disconnect" : "Connect");
        hostField.setEnabled(!isConnected);
        portField.setEnabled(!isConnected);
        nickField.setEnabled(!isConnected);
        inputField.setEnabled(isConnected);
        sendButton.setEnabled(isConnected);
        questionField.setEnabled(isConnected);
        askButton.setEnabled(isConnected);
        upvoteButton.setEnabled(isConnected);
        answerButton.setEnabled(isConnected);
        newPollButton.setEnabled(isConnected);
        if (isConnected) {
            nickField.setText(nick);
            showNotice("Connected as " + nick + ".", false);
            inputField.requestFocusInWindow();
        } else {
            usersModel.clear();
            usersTitle.setText("Online (0)");
            showNotice("Not connected.", true);
        }
    }

    private void chatReceived(String time, String sender, String text, boolean mine) {
        append("[" + time + "] ", style(MUTED, false, false));
        append(sender + ": ", style(mine ? GREEN : new Color(65, 175, 235), true, false));
        append(text + "\n", style(TEXT, false, false));
    }

    private void systemMessage(String time, String text, boolean error) {
        append("[" + time + "] " + text + "\n", style(error ? RED : new Color(90, 190, 140), false, true));
    }

    private void usersChanged(List<String> users) {
        usersModel.clear();
        for (String u : users) usersModel.addElement(u);
        usersTitle.setText("Online (" + users.size() + ")");
    }

    private void questionsChanged(List<QuestionItem> questions) {
        int selectedId = selectedQuestionId();
        currentQuestions = new ArrayList<>(questions);
        queueTableModel.setRowCount(0);
        int reselect = -1;
        for (int i = 0; i < questions.size(); i++) {
            QuestionItem q = questions.get(i);
            queueTableModel.addRow(new Object[]{q.id(), q.text(), q.asker(), q.votes(),
                    q.upvotedByMe() ? "✓" : "", q.answered() ? "Answered" : "Open"});
            if (q.id() == selectedId) reselect = i;
        }
        if (reselect >= 0) queueTable.setRowSelectionInterval(reselect, reselect);
    }

    private int selectedQuestionId() {
        int row = queueTable.getSelectedRow();
        return (row >= 0 && row < currentQuestions.size()) ? currentQuestions.get(row).id() : -1;
    }

    private void renderPolls(List<PollItem> polls) {
        pollsPanel.removeAll();
        if (polls.isEmpty()) {
            JLabel empty = new JLabel("No polls yet. Press \"+ New poll\" to ask the group something.");
            empty.setForeground(TEXT);
            empty.setBorder(BorderFactory.createEmptyBorder(12, 4, 0, 0));
            pollsPanel.add(empty);
        }
        for (int i = polls.size() - 1; i >= 0; i--) {   // newest first
            pollsPanel.add(buildPollCard(polls.get(i)));
            pollsPanel.add(Box.createVerticalStrut(8));
        }
        pollsPanel.revalidate();
        pollsPanel.repaint();
    }

    private JPanel buildPollCard(PollItem p) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(SECTION_BLUE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("<html><b>#" + p.id() + "  " + escape(p.question()) + "</b></html>");
        title.setForeground(TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        JLabel meta = new JLabel((p.open() ? "OPEN" : "CLOSED") + " · started by " + p.creator()
                + " · " + p.total() + " vote(s)");
        meta.setForeground(MUTED);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(meta);
        card.add(Box.createVerticalStrut(6));

        int total = p.total();
        for (int i = 0; i < p.options().size(); i++) {
            int count = p.counts().get(i);
            int pct = total == 0 ? 0 : Math.round(100f * count / total);

            JButton vote = new JButton(p.myVote() == i ? "Voted ✓" : "Vote");
            vote.setEnabled(connected && p.open() && p.myVote() < 0);
            vote.setPreferredSize(new Dimension(95, 28));
            styleButton(vote, GREEN);
            final int pollId = p.id();
            final int optionIndex = i;
            vote.addActionListener(e -> onVote(pollId, optionIndex));

            JProgressBar bar = new JProgressBar(0, Math.max(total, 1));
            bar.setValue(count);
            bar.setStringPainted(true);
            bar.setForeground(BLUE);
            bar.setBackground(SECTION_BLUE_DARK);
            bar.setString(p.options().get(i) + "  -  " + count + " (" + pct + "%)");

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(true);
            row.setBackground(SECTION_BLUE);
            row.add(vote, BorderLayout.WEST);
            row.add(bar, BorderLayout.CENTER);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(row);
            card.add(Box.createVerticalStrut(4));
        }
        if (p.open() && p.canClose()) {
            JButton close = new JButton("Close poll");
            final int pollId = p.id();
            styleButton(close, RED);
            close.addActionListener(e -> onClosePoll(pollId));
            close.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(Box.createVerticalStrut(4));
            card.add(close);
        }
        return card;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static AttributeSet style(Color color, boolean bold, boolean italic) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        return a;
    }

    private void append(String text, AttributeSet style) {
        StyledDocument doc = transcript.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) { }
        transcript.setCaretPosition(doc.getLength());
    }
}
