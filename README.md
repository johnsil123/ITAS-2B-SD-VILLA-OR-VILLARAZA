# 📚 Study Circle – LAN Study Room

**ITAS1 Final Project · Network Programming · Java LAN Chat Application**

## 1. Project overview
Study Circle is a Java (Swing) client/server chat application for classmates reviewing a subject together
over a local network. Besides normal group chat it adds a **shared question queue** and **live group polls**.

| | |
|---|---|
| Course / Section | ITAS1 – `BSIT-2B-SD` |
| Member 1 | Villa-or – GitHub: [@mykaandreavillaor-cmyk](https://github.com/mykaandreavillaor-cmyk) |
| Member 2 | Villaraza – GitHub: [@johnsil123](https://github.com/johnsil123) |
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

## 8. Screenshots

### Connected clients and online list
![Connected clients](<img width="1366" height="768" alt="CONNECTONSCREEN" src="https://github.com/user-attachments/assets/b484cd07-cebb-430e-9280-784b83cf3458" />
)

### Group chat
![Group chat](<img width="1366" height="768" alt="GROUPCHAT" src="https://github.com/user-attachments/assets/d1ca48d9-c514-4094-8c0b-7e6499ea7ad0" />
)

### Server window
![Server window](<img width="1366" height="719" alt="CLIENT CONNECTION" src="https://github.com/user-attachments/assets/52005463-223e-45e4-ad67-ee99be02bc0f" />
)

### Question Queue (original feature 1)
![Question Queue](<img width="1366" height="768" alt="QUESTION QUEUE" src="https://github.com/user-attachments/assets/b32ce05f-6541-47a9-a8dd-aecb28b9a987" />
)

### Group Poll with live results (original feature 2)
![Group Poll](<img width="1366" height="768" alt="GROUP POLL" src="https://github.com/user-attachments/assets/cf65dcde-e025-4b06-81a6-fa7603f494c3" />
)

### Chat continues after a user leaves
![Chat continues](<img width="1363" height="721" alt="CHAT CONTINUE" src="https://github.com/user-attachments/assets/7f1ccdf6-b848-4b7d-aa21-8eafff55f991" />
)

### Clean exit (`/exit`)
![Clean exit](<img width="1366" height="721" alt="EXIT" src="https://github.com/user-attachments/assets/d5afdc64-fc09-4cab-a6ac-9cddfbba48cf" />
()
)

### Error handling
Server unavailable:

![Server unavailable](<img width="960" height="500" alt="ERRORHANDLING" src="https://github.com/user-attachments/assets/a7fbce60-0f94-44a4-92b7-22e42b0ffafd" />
)

![Server unavailable (2)](<img width="959" height="473" alt="error handling" src="https://github.com/user-attachments/assets/c3d36a8c-6b1e-4168-a33b-d6420bf89f40" />
)

Invalid host, wrong port and blank IP:

![Invalid host](<img width="1366" height="765" alt="invalid host" src="https://github.com/user-attachments/assets/1e1ebe06-07d5-4370-a4b3-ae0695f48298" />
)

![Wrong port](<img width="1366" height="768" alt="invald port" src="https://github.com/user-attachments/assets/e12c8ee3-b07f-4bf5-847b-9e45b43e941b" />
)

![Blank IP](<img width="1366" height="768" alt="blank ip" src="https://github.com/user-attachments/assets/1bb530fb-eaa7-4821-917a-8ada7e9323e4" />
)

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
|2026-11-25 | Villa-or (@mykaandreavillaor-cmyk) | Server: accept loop, thread per client, protocol | |
|2026-11-25 | Villaraza (@johnsil123) | Client: Swing window, connect, Poll panels and Question Queue | |

## 11. Credits, libraries & AI assistance
- Libraries: Java SE only (`java.net`, `java.util.concurrent`, Swing).
- AI assistance: `Claude was used in this project to help design and review parts of the code and to help organize this README.`.
- Tutorials / references used: `<list or "none">`.
