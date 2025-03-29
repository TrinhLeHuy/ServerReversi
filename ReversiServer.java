import java.io.*;
import java.net.*;
import java.util.*;

public class ReversiServer {
    // private static final int PORT = 5000;
    private ServerSocket serverSocket;
    // Quản lý các phòng bằng roomId
    private static Map<String, Room> rooms = new HashMap<>();
    

    public static void main(String[] args) {
    //     try (ServerSocket serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))) {
    //         System.out.println("Server Reversi chạy trên cổng " + PORT);
    //         while (true) {
    //             Socket socket = serverSocket.accept();
    //             System.out.println("Client kết nối: " + socket);
    //             new Thread(new ClientHandler(socket)).start();
    //         }
    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
    // }

    static class Room {
        String roomId;
        List<ClientHandler> players = new ArrayList<>();
        int[][] board = new int[8][8];
        int currentTurn = 1; // Player 1 (Đen) bắt đầu

        // Biến cho chức năng PLAY_AGAIN (nếu cần mở rộng)
        boolean replayPlayer1 = false;
        boolean replayPlayer2 = false;

        Room(String roomId) {
            this.roomId = roomId;
            resetBoard();
        }

        void resetBoard() {
            board = new int[8][8];
            board[3][3] = 2;
            board[3][4] = 1;
            board[4][3] = 1;
            board[4][4] = 2;
            currentTurn = 1;
            replayPlayer1 = false;
            replayPlayer2 = false;
        }

        boolean isFull() {
            return players.size() == 2;
        }

        synchronized void changeTurn() {
            currentTurn = (currentTurn == 1) ? 2 : 1;
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private Room currentRoom;
        private int playerId;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                out.println("WELCOME");

                String command;
                while ((command = in.readLine()) != null) {
                    // Loại bỏ khoảng trắng đầu/cuối và chuyển thành chữ hoa để xử lý không phân biệt
                    command = command.trim();
                    System.out.println("Nhận từ client: " + command);
                    String[] parts = command.toUpperCase().split(":");
                    String cmd = parts[0];
                    switch (cmd) {
                        case "CREATE":
                            if (parts.length < 2) {
                                out.println("ERROR: ROOM ID THIẾU");
                            } else {
                                createRoom(parts[1]);
                            }
                            break;
                        case "JOIN":
                            if (parts.length < 2) {
                                out.println("ERROR: ROOM ID THIẾU");
                            } else {
                                joinRoom(parts[1]);
                            }
                            break;
                        case "START":
                            startGame();
                            break;
                        case "MOVE":
                            if (parts.length < 3) {
                                out.println("ERROR: MOVE THIẾU THAM SỐ");
                            } else {
                                int row = Integer.parseInt(parts[1]);
                                int col = Integer.parseInt(parts[2]);
                                handleMove(row, col);
                            }
                            break;
                        case "PLAY_AGAIN":
                            handlePlayAgain();
                            break;
                        case "EXIT":
                            handleExit();
                            break;
                        case "RETURN_MENU":
                            handleReturnMenu();
                            break;
                        case "LIST":
                            listRooms();
                            break;
                        default:
                            out.println("ERROR: LỆNH KHÔNG HỢP LỆ");
                    }
                }
            } catch (IOException e) {
                System.out.println("Client mất kết nối: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void createRoom(String roomId) {
            synchronized (rooms) {
                if (rooms.containsKey(roomId)) {
                    out.println("ERROR: PHÒNG ĐÃ TỒN TẠI");
                    return;
                }
                Room room = new Room(roomId);
                this.playerId = 1;
                room.players.add(this);
                rooms.put(roomId, room);
                currentRoom = room;
                out.println("ROOM_CREATED:" + roomId);
                out.println("ASSIGN:" + playerId);
            }
        }

        private void joinRoom(String roomId) {
            synchronized (rooms) {
                Room room = rooms.get(roomId);
                if (room == null || room.isFull()) {
                    out.println("ERROR: PHÒNG KHÔNG TỒN TẠI HOẶC ĐÃ ĐẦY");
                    return;
                }
                this.playerId = 2;
                room.players.add(this);
                currentRoom = room;
                out.println("JOINED:" + roomId);
                out.println("ASSIGN:" + playerId);
                if (room.isFull()) {
                    for (ClientHandler p : room.players) {
                        p.out.println("READY:" + roomId);
                    }
                }
            }
        }

        private void startGame() {
            if (currentRoom == null || !currentRoom.isFull() || this.playerId != 1) {
                out.println("ERROR: CHỈ PLAYER 1 MỚI START");
                return;
            }
            for (ClientHandler p : currentRoom.players) {
                p.out.println("START");
            }
            for (ClientHandler p : currentRoom.players) {
                p.out.println("TURN:" + currentRoom.currentTurn);
            }
        }

        private void handleMove(int row, int col) {
            if (currentRoom == null) {
                out.println("ERROR: BẠN CHƯA Ở TRONG PHÒNG");
                return;
            }
            if (this.playerId != currentRoom.currentTurn) {
                out.println("ERROR: CHƯA ĐẾN LƯỢT CỦA BẠN");
                return;
            }
            if (currentRoom.board[row][col] != 0) {
                out.println("ERROR: Ô NÀY ĐÃ CÓ QUÂN");
                return;
            }
            List<int[]> flips = getFlippablePieces(row, col, playerId, currentRoom.board);
            if (flips.isEmpty()) {
                out.println("ERROR: NƯỚC ĐI KHÔNG HỢP LỆ");
                return;
            }
            currentRoom.board[row][col] = playerId;
            for (int[] pos : flips) {
                currentRoom.board[pos[0]][pos[1]] = playerId;
            }
            currentRoom.changeTurn();
            int nextTurn = currentRoom.currentTurn;
            broadcastBoardState(row, col, flips, playerId, nextTurn);
            checkGameState();
        }

        private void checkGameState() {
            int currPlayer = currentRoom.currentTurn;
            boolean canMove = hasValidMove(currentRoom.board, currPlayer);
            if (!canMove) {
                // Skip lượt nếu không có nước đi
                currentRoom.changeTurn();
                int other = currentRoom.currentTurn;
                boolean otherCanMove = hasValidMove(currentRoom.board, other);
                if (!otherCanMove) {
                    endGame();
                } else {
                    broadcastTurn(other);
                }
            }
        }

        private void endGame() {
            int count1 = 0, count2 = 0;
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    if (currentRoom.board[i][j] == 1)
                        count1++;
                    if (currentRoom.board[i][j] == 2)
                        count2++;
                }
            }
            String result;
            if (count1 > count2)
                result = "WIN:1 win";
            else if (count2 > count1)
                result = "WIN:2 win";
            else
                result = "WIN:TIE";
            for (ClientHandler p : currentRoom.players) {
                p.out.println(result);
            }
        }

        private boolean hasValidMove(int[][] board, int player) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    if (board[r][c] == 0) {
                        List<int[]> flips = getFlippablePieces(r, c, player, board);
                        if (!flips.isEmpty())
                            return true;
                    }
                }
            }
            return false;
        }

        private void broadcastBoardState(int row, int col, List<int[]> flips, int player, int nextTurn) {
            synchronized (currentRoom) {
                for (ClientHandler p : currentRoom.players) {
                    p.out.println("MOVE:" + row + ":" + col + ":" + player);
                    for (int[] f : flips) {
                        p.out.println("FLIP:" + f[0] + ":" + f[1] + ":" + player);
                    }
                    p.out.println("TURN:" + nextTurn);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        for (int j = 0; j < 8; j++) {
                            sb.append(currentRoom.board[i][j]);
                            if (j < 7)
                                sb.append(",");
                        }
                        if (i < 7)
                            sb.append(";");
                    }
                    p.out.println("BOARD_STATE:" + sb.toString());
                }
            }
        }

        private void broadcastTurn(int turn) {
            synchronized (currentRoom) {
                for (ClientHandler p : currentRoom.players) {
                    p.out.println("TURN:" + turn);
                }
            }
        }

        private List<int[]> getFlippablePieces(int row, int col, int player, int[][] board) {
            int opponent = (player == 1) ? 2 : 1;
            List<int[]> flipped = new ArrayList<>();
            int[][] directions = {
                    { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 },
                    { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }
            };
            for (int[] dir : directions) {
                int r = row + dir[0], c = col + dir[1];
                List<int[]> potential = new ArrayList<>();
                while (r >= 0 && r < 8 && c >= 0 && c < 8 && board[r][c] == opponent) {
                    potential.add(new int[] { r, c });
                    r += dir[0];
                    c += dir[1];
                }
                if (r >= 0 && r < 8 && c >= 0 && c < 8 && board[r][c] == player) {
                    flipped.addAll(potential);
                }
            }
            return flipped;
        }

        private void handlePlayAgain() {
            // Nếu người chơi này đã gửi yêu cầu PLAY_AGAIN rồi, bỏ qua
            if ((playerId == 1 && currentRoom.replayPlayer1) ||
                (playerId == 2 && currentRoom.replayPlayer2)) {
                return;
            }
        
            // Đánh dấu người chơi đã gửi yêu cầu PLAY_AGAIN
            if (playerId == 1)
                currentRoom.replayPlayer1 = true;
            else if (playerId == 2)
                currentRoom.replayPlayer2 = true;
        
            // Nếu cả hai đã đồng ý chơi lại, reset bàn cờ và bắt đầu game mới
            if (currentRoom.replayPlayer1 && currentRoom.replayPlayer2) {
                currentRoom.resetBoard();
                for (ClientHandler p : currentRoom.players) {
                    p.out.println("NEW_GAME");
                    p.out.println("TURN:" + currentRoom.currentTurn);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        for (int j = 0; j < 8; j++) {
                            sb.append(currentRoom.board[i][j]);
                            if (j < 7)
                                sb.append(",");
                        }
                        if (i < 7)
                            sb.append(";");
                    }
                    p.out.println("BOARD_STATE:" + sb.toString());
                }
                // Reset lại các cờ khi game mới bắt đầu
                currentRoom.replayPlayer1 = false;
                currentRoom.replayPlayer2 = false;
            
                }
            }
        
        private void handleExit() {
            // Trước khi đóng kết nối, đưa người chơi và đối thủ về menu
            if (currentRoom != null) {
                synchronized (currentRoom) {
                    returnPlayersToMenu();
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        private void handleReturnMenu() {
            if (currentRoom != null) {
                synchronized (currentRoom) {
                    returnPlayersToMenu();
                    synchronized (rooms) {
                        rooms.remove(currentRoom.roomId);
                    }
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        private void listRooms() {
            synchronized (rooms) {
                if (rooms.isEmpty()) {
                    out.println("ROOM_LIST:EMPTY");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Room room : rooms.values()) {
                        // Hiển thị roomId và số lượng người chơi (ví dụ: "ROOM1(1)")
                        sb.append(room.roomId).append("(").append(room.players.size()).append("),");
                    }
                    if (sb.length() > 0)
                        sb.setLength(sb.length() - 1);
                    out.println("ROOM_LIST:" + sb.toString());
                }
            }
        }

        // Phương thức hỗ trợ đưa tất cả các client trong phòng về menu
        private void returnPlayersToMenu() {
            if (currentRoom != null) {
                for (ClientHandler p : currentRoom.players) {
                    p.out.println("EXIT_TO_MENU");
                }
            }
        }
        
        private void cleanup() {
            if (currentRoom != null) {
                synchronized (currentRoom) {
                    currentRoom.players.remove(this);
                    if (currentRoom.players.size() == 1) {
                        // Gửi thông báo chiến thắng cho người chơi còn lại
                        ClientHandler remainingPlayer = currentRoom.players.get(0);
                        remainingPlayer.out.println("WIN: Opponent disconnected, you win!");
                    } else if (!currentRoom.players.isEmpty()) {
                        returnPlayersToMenu();
                    }
                    synchronized (rooms) {
                        rooms.remove(currentRoom.roomId);
                    }
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
    }
}
