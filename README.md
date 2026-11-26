# 📚 Study Circle – LAN Study Room

**ITAS1 Final Project · Network Programming · Java LAN Chat Application**

## 1. Project overview
Study Circle is a Java (Swing) client/server chat application for classmates reviewing a subject together
over a local network. Besides normal group chat it adds a **shared question queue** and **live group polls**.

| | |
|---|---|
| Course / Section | ITAS1 – `<Section>` |
| Member 1 | `<Full name>` – GitHub: `<username>` |
| Member 2 | `<Full name>` – GitHub: `<username>` |
| Theme | Study Circle |

## 2. Concept proposal & approval
- **Audience:** classmates reviewing for a quiz or exam in the same room / Wi-Fi.
- **Problem:** in an ordinary chat, questions get buried under other messages and nobody knows which
  question matters most; group decisions ("when do we meet?") become scrolling arguments.
- **Original feature 1 – Shared Question Queue:** anyone adds a question; everyone can upvote; the queue
  re-sorts for all clients (most votes first); anyone can mark a question answered (moves to the bottom).
  One upvote per person per question; asking counts as the first vote.
- **Original feature 2 – Group Poll with live results:** anyone starts a poll (2–6 options); every client
  sees live bars/percentages; one vote per person; only the creator closes it (anyone may if the creator left).
- **Sample screens:** see section 8.
- **Instructor approval:** `<Instructor name> – <date> – <approved / notes>`

## 3. Core features (required)
Server + 3 concurrent clients (thread per client) · configurable IP/port · nickname validation (non-blank,
unique case-insensitive, 1–20 chars: letters, digits, `_ - .`) · group chat broadcast once to everyone incl. sender
with nickname + time · live online list · join/leave/rename announcements · `/name`, `/exit`, `/help` ·
blank-message rejection · helpful errors for bad IP/port, unreachable server, lost connection ·
sockets and streams released on exit · GUI never blocks on the network.

## 4. Project structure
```text
src/Server.java    server + server window (one file)
src/Client.java    client window + networking (one file)
compile.bat    compiles both into the out/ folder
server.bat     runs the server  (optional argument: port, default 5000)
client.bat     runs a client
README.md
.gitignore
```
`src/Server.java` accepts clients (one thread per client), keeps the shared room state (users, question queue, polls),
and shows a Swing dashboard. `src/Client.java` is the Swing chat window; sockets run on background threads and every
screen update runs on the Swing thread.

### Message path (client → server → everyone)
1. User presses **Send** → `Client` validates the text and queues `MSG|text` on the sender thread (not the GUI thread).
2. The server's handler thread for that client reads the line and calls the room's `chat(...)`.
3. The room (synchronized) validates and broadcasts `CHAT|time|sender|text` to **every** member, including the sender.
4. Each client's reader thread receives it → `SwingUtilities.invokeLater` → printed in the chat tab.

### Message format
One line per message: `TYPE|field|field|...`. Every field is URL-encoded, so `|`, newlines and non-ASCII text are safe.
Lists (`QUEUE`, `POLLS`) send the full snapshot, each item as comma-joined encoded sub-fields.

| Direction | Message | Meaning |
|---|---|---|
| C→S | `JOIN\|nick` | Join with nickname |
| C→S | `MSG\|text` | Chat message |
| C→S | `NAME\|newnick` | `/name` |
| C→S | `HELP`, `EXIT` | `/help`, `/exit` |
| C→S | `ASK\|text` | Add question |
| C→S | `UPVOTE\|id`, `ANSWER\|id` | Upvote / mark answered |
| C→S | `POLL_NEW\|question\|opt1\|opt2…` | Start poll |
| C→S | `VOTE\|pollId\|optionIndex`, `POLL_CLOSE\|pollId` | Vote / close |
| S→C | `WELCOME\|nick` | Join or rename accepted |
| S→C | `ERR\|message` | Validation error |
| S→C | `CHAT\|time\|sender\|text` | Broadcast chat |
| S→C | `SYS\|time\|text` | Join/leave/rename/feature events, help |
| S→C | `USERS\|n1\|n2…` | Online list |
| S→C | `QUEUE\|id,asker,text,votes,answered,youUpvoted\|…` | Question queue snapshot (sorted) |
| S→C | `POLLS\|id,question,open,creator,myVote,canClose,opt,count,opt,count…\|…` | Poll snapshot |
| S→C | `BYE` | Clean exit acknowledged |

### Concurrency & reliability notes
- Thread per client (`Executors.newCachedThreadPool`); one client dropping only ends its own thread (`finally` → `leave()` + close socket).
- All shared state lives in `ServerModel`; every method is `synchronized`, so concurrent writes never corrupt state and broadcasts never interleave.
- `ClientHandler.send` is `synchronized`, so two threads can't mix bytes on one socket.
- Client: connecting runs on a background thread; sends go through a single-thread executor; all Swing updates run on the EDT.

## 5. Setup instructions
**Requirements:** JDK 17 or newer (tested with 21). No external libraries, no build tool needed.

**Windows:**
1. Double-click `compile.bat` (creates the `out` folder). `server.bat` and `client.bat` also compile automatically the first time.
2. Run `server.bat` (opens the **server window**; optional argument: port, e.g. `server.bat 5001`).
3. Run `client.bat` once per client (as many as you need).

The server can also run without a window: `server.bat --console [port]` (this happens automatically on a machine with no display).
Manual: `javac -d out src/Server.java src/Client.java`, then `java -cp out Server` and `java -cp out Client`.

**LAN demo (two computers):**
1. Both computers on the same Wi-Fi/LAN.
2. Computer A: start the server. The window shows the LAN address(es) to use, e.g. `192.168.x.x`.
3. Computer A: start 1 or 2 clients (use `localhost` or the LAN IP). Computer B: start the other client(s) and enter A's LAN IP, port `5000`.
4. Three clients in total, each with a different nickname.

**Troubleshooting:** *Connection refused* → server not running, wrong IP/port, or the Windows Firewall is blocking Java
(allow "Java(TM) Platform SE binary" on Private networks, or allow TCP port 5000). *Timed out* → computers are on different networks / guest Wi-Fi with client isolation.
*Port already in use* → run the server with another port, e.g. `server.bat 5001`.

## 6. Commands
| Command | Effect |
|---|---|
| `/name <newname>` | Change nickname (validated, unique); everyone is notified |
| `/exit` | Leave cleanly (sockets closed, others see "left") |
| `/help` | Show help (sent only to you) |

## 7. How to use the original features
- **Question Queue tab:** type a question → **Ask**. Select a row → **▲ Upvote selected** or **✓ Mark answered**.
- **Group Polls tab:** **+ New poll** → fill question and 2–4 options → OK. Press **Vote** on an option; bars update on every client. Creator presses **Close poll**.

## 8. Screenshots  *(replace with your own, stored in `/docs`)*
- `docs/01-connect-and-chat.png` – three clients chatting, online list
- `docs/02-question-queue.png` – queue synchronized on two clients
- `docs/03-group-poll.png` – poll with live results on two clients
- `docs/04-error-recovery.png` – unavailable server / duplicate nickname error

## 9. Test results  *(fill in "Actual" and "Status" while testing – required evidence)*
| # | Test | Steps | Expected | Actual | Status |
|---|---|---|---|---|---|
| 1 | Three concurrent clients | Start server, connect 3 clients | All appear in online list | | |
| 2 | Broadcast | Client A sends "hi" | A, B, C each show it once with name + time | | |
| 3 | Online list updates | Join / leave a client | List and "joined/left" notice update everywhere | | |
| 4 | Valid nickname change | `/name newbie` | All clients show the new name | | |
| 5 | Duplicate nickname | `/name <existing>` (also try different case) | Error; name unchanged | | |
| 6 | Blank message | Press Send with empty field | Nothing sent, notice shown | | |
| 7 | Invalid IP / port | Port `abc`, port `99999`, blank IP | Error dialog, no crash | | |
| 8 | Unavailable server | Connect with server stopped | "Could not connect" dialog, GUI responsive | | |
| 9 | Clean `/exit` | Type `/exit` | Others see "left"; nickname reusable | | |
| 10 | Abrupt disconnect | Kill a client's process | Server keeps running; others notified; chat continues | | |
| 11 | Chat continues | After test 10 | Remaining clients still exchange messages | | |
| 12 | Question queue sync | A asks; B upvotes; C marks answered (3 clients) | All three show same order/votes/status | | |
| 13 | Duplicate upvote | B upvotes same question twice | Second attempt rejected | | |
| 14 | Poll sync | A creates poll; B and C vote | Counts/percentages identical on all clients | | |
| 15 | Double vote / close rules | B votes twice; C tries to close | Both rejected; creator can close | | |
| 16 | Real LAN | Two computers, 3 clients | Full chat + both features work | | |

*Automated check done during development:* a scripted 3-client socket test against the server confirmed tests 2, 4, 5, 6, 9, 10, 11, 12, 13, 14, 15
(special characters such as `|` and newlines in messages also round-tripped correctly). GUI and LAN tests (1, 3, 7, 8, 16) must be run by the team.

## 10. Contribution log
| Date | Member | Task | Commit link |
|---|---|---|---|
| | Member 1 | | |
| | Member 2 | | |

## 11. Credits, libraries & AI assistance
- Libraries: Java SE only (`java.net`, `java.util.concurrent`, Swing).
- AI assistance: `<state exactly which parts were generated / assisted by an AI tool and what you changed>`.
- Tutorials / references used: `<list or "none">`.
