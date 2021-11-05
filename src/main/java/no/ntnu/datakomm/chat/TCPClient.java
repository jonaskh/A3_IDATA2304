package no.ntnu.datakomm.chat;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Responsible for connecting to a server, and handling the server responses.
 * Notifies event listeners.
 */
public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    private String lastError = null;

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        boolean success = false;
        if (!isConnectionActive()) {
            try {
                connection = new Socket(host, port);
                InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                fromServer = new BufferedReader(reader);
                toServer = new PrintWriter(connection.getOutputStream(), true);
                success = true;
            } catch (IOException e) {
                System.err.println("Failed to connect to server: " + host + ", Port: " + port);
            }
        }
        return success;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        if (isConnectionActive()) {
            try {
                connection.close();
                connection = null;
                onDisconnect();
            } catch (IOException e) {
                System.err.println("Could not disconnect, no active socket found...");
            }
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        boolean success = false;
        if (isConnectionActive()) {
            if (cmd == null) {
                System.err.println("Command cannot be null...");
            } else if (cmd.isEmpty()) {
                System.err.println("Command cannot be empty...");
            } else {
                toServer.println(cmd);
                success = true;
            }
        }
        return success;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        boolean success = false;

        if (message == null) {
            System.err.println("Public message cannot be null...");
        } else if (message.isEmpty()) {
            System.err.println("Public message cannot be empty...");
        } else if (sendCommand(message)) {
            success = true;
        }

        return success;
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        if (username == null) {
            System.err.println("Username cannot be null");
        } else if (username.isBlank()) {
            System.err.println("Username cannot be blank");
        } else {
            sendCommand("login " + username);
        }
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        if (isConnectionActive()) {
            sendCommand("users");
        }
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        boolean success = false;
        if (isConnectionActive()) {
            if (recipient == null) {
                System.err.println("Recipient cannot be null...");
            } else if (recipient.isEmpty()) {
                System.err.println("Recipient cannot be empty...");
            } else if (message == null) {
                System.err.println("Message cannot be null...");
            } else if (message.isEmpty()) {
                System.err.println("Message cannot be empty...");
            } else if (sendCommand("privmsg " + recipient + " " + message)) {
                success = true;
            }
        }
        return success;
    }


    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        if (isConnectionActive()) {
            sendCommand("help");
        }
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String response = null;
        if (isConnectionActive()) {
            try {
                response = fromServer.readLine();
                System.out.println(response);
            } catch (IOException e) {
                System.err.println("Failed to read server response...");
            }
        }
        return response;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(() -> {
            parseIncomingCommands();
        });
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            String message = waitServerResponse();
            if (message != null) {
                String[] splitMessage = message.split(" ");
                String command = splitMessage[0];
                switch (command) {
                    case "loginok":
                        onLoginResult(true, "login successful");
                        break;
                    case "loginerr":
                        onLoginResult(false, "login failed");
                        break;
                    case "users":
                        //Very inefficient, but only way I found to remove first index "users"
                        List<String> listOfUsers = new ArrayList<>(Arrays.asList(splitMessage));
                        listOfUsers.remove("users");
                        splitMessage = listOfUsers.toArray(new String[0]);
                        onUsersList(splitMessage);
                        break;
                    case "privmsg":
                        onMsgReceived(true, splitMessage[1], splitMessage[2]);
                        break;
                    case "msg":
                        onMsgReceived(false, splitMessage[1], splitMessage[2]);
                        break;

                    case "supported":
                        List<String> listOfCommands = new ArrayList<>(Arrays.asList(message.split(" ")));
                        listOfCommands.remove("supported");
                        splitMessage = listOfCommands.toArray(new String[0]);
                        onSupported(splitMessage);
                        break;
                    case "cmderr":
                        onCmdError(message);
                        break;
                    case "msgerr":
                        onMsgError(message);
                        break;
                    case "modeok":
                        // Do nothing?
                        break;
                    case "msgok":
                        // DO nothing??
                        break;
                    case "joke":
                        String messageToDisplay = "";
                        for(int i = 1; i < splitMessage.length; i++) {
                            messageToDisplay += splitMessage[i] + " ";
                        }
                        onJoke(messageToDisplay);
                        break;
                    default:
                        System.err.println("Could not understand response from server");
                        break;
                }
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener The listener to be added
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener The listener to be removed.
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        // Hint: all the onXXX() methods will be similar to onLoginResult()
        for (ChatListener l : listeners) {
            l.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for (ChatListener l : listeners) {
            l.onUserList(users);
        }

    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage message = new TextMessage(sender, priv, text);
        for (ChatListener l : listeners) {
            l.onMessageReceived(message);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for (ChatListener l : listeners) {
            l.onSupportedCommands(commands);
        }
    }

    /**
     * Notify listeners that a joke response was received from the server
     *
     * @param joke The joke received from server
     */
    private void onJoke(String joke) {
        for (ChatListener l: listeners) {
            l.onJoke(joke);
        }
    }
}
