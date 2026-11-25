# StudyCircle – LAN Study Room

**Java Swing Client–Server Application | Network Programming Project**

## 1. Project Overview

StudyCircle is a Java Swing application that allows classmates connected to the same local area network (LAN) to communicate in a shared study room. It combines group messaging with study-focused tools so members can discuss lessons, organize questions, and make group decisions.

### Project Details

| Item | Description |
|---|---|
| Project Name | StudyCircle |
| Application Type | LAN-based Client–Server |
| Programming Language | Java |
| GUI Framework | Java Swing |
| Architecture | Model–View–Controller (MVC) |
| Main Functions | Group Chat, Shared Question Queue, Group Poll |

## 2. Objectives

- Provide a simple communication platform for students on the same network.
- Allow connected users to exchange messages in real time.
- Keep study questions organized in a shared queue.
- Let members create polls and view group voting results.
- Demonstrate client–server communication using Java sockets.

## 3. Main Features

### Group Chat
Users can join the same room using a server IP address and port. Messages are broadcast to connected clients, and the interface displays chat activity.

### Shared Question Queue
Members can submit questions for the group. The queue helps keep important questions visible instead of letting them get lost in the chat.

### Group Poll
A member can create a poll for the group. Other connected members can vote and view the poll results.

### Connection and Input Validation
The application checks connection details and displays feedback for invalid host names, IP addresses, or port values.

### Exit
Users can leave the chat through the application's exit function.

## 4. Application Architecture – MVC

StudyCircle follows the Model–View–Controller approach:

| MVC Part | Responsibility |
|---|---|
| Model | Stores and manages application data, such as chat messages, questions, polls, and connection state. |
| View | Displays the Swing interface, including the connection screen, chat area, question queue, and poll panel. |
| Controller | Handles user actions and coordinates communication between the View, Model, and client/server logic. |

The client communicates with the server through a network connection. The server manages connected clients and distributes shared updates to the room.

## 5. Requirements

- Java Development Kit (JDK) installed.
- The server and client computers must be connected to the same Wi-Fi or LAN for a local network demonstration.
- The server must be running before clients connect.
- The client needs the correct server IP address and port number.

## 6. How to Run

1. Open the StudyCircle project in your Java IDE.
2. Run the **Server** application first.
3. Check the server's displayed network address and port.
4. Run the **Client** application.
5. Enter a nickname and the server IP address and port.
6. Connect another client using the same server address.
7. Test the group chat, question queue, and group poll.
8. Use the exit option when finished.

For computers on the same machine, `localhost` can be used as the server address. For another computer on the LAN, use the server computer's LAN IP address.

## 7. Screenshots

The following screenshots show the application's interface and selected test scenarios.

### 7.1 Connection and Client Screens

**Connection Screen**

![Connection screen](screenshots/01-connection-screen.png)

**Client Connection**

![Client connection](screenshots/02-client-connection.png)

### 7.2 Chat Screens

**Group Chat**

![Group chat](screenshots/03-group-chat.png)

**Chat Continues**

![Chat continues](screenshots/04-chat-continues.png)

### 7.3 Study Features

**Shared Question Queue**

![Shared Question Queue](screenshots/05-question-queue.png)

**Group Poll**

![Group Poll](screenshots/06-group-poll.png)

### 7.4 Error Handling and Validation

**Error Handling**

![Error handling](screenshots/07-error-handling.png)

**Additional Error Handling Example**

![Error handling detail](screenshots/08-error-handling-detail.png)

**Blank IP Validation**

![Blank IP validation](screenshots/09-blank-ip.png)

**Invalid Port Validation**

![Invalid port validation](screenshots/10-invalid-port.png)

**Invalid Host or Port**

![Invalid host or port](screenshots/11-invalid-host-or-port.png)

### 7.5 Exit Screen

**Client Exit**

![Client exit](screenshots/12-exit.png)

## 8. Testing Summary

The screenshots above provide visual evidence of the connection interface, client connection, group chat, study tools, input validation, error handling, and exit screen.

| Test Area | What to Check |
|---|---|
| Server connection | Client connects using the correct server IP and port. |
| Group chat | Messages appear in the chat interface for connected users. |
| Question queue | Questions are displayed in the shared queue. |
| Group poll | Poll questions and voting results are displayed. |
| Input validation | Invalid or blank connection details produce an error message. |
| Exit | A client can leave the application using the exit function. |

## 9. Troubleshooting

| Problem | Suggested Check |
|---|---|
| Client cannot connect | Confirm that the server is running and the IP address and port are correct. |
| Another computer cannot connect | Check that both computers are on the same network and that the firewall allows the Java application. |
| Invalid host or port message | Check the entered host address and use a valid port number. |
| Server does not start | Check whether the selected port is already being used by another application. |

## 10. Conclusion

StudyCircle demonstrates how a Java Swing client–server application can support group learning over a local network. Its chat, shared question queue, and group poll features provide students with a common space for communication and collaboration. The project also demonstrates the use of MVC organization and basic network connection handling.

## 11. Credits

- Developed as a Java Network Programming project.
- Built using Java and Swing.
- Project screenshots are included in the `screenshots` folder.
