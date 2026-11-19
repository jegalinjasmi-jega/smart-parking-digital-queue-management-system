/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */

/**
 *
 * @author Admin
 */

package smartparking;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class SmartParkingServer {

    // Used by the existing parking availability and reservation APIs.
    private static final Map<String, String> slots = new HashMap<>();

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found!", e);
        }

        String url = "jdbc:mysql://...";
        String username = "...";

        // Replace this placeholder with your own MySQL password locally.
        String password = "...";

        return DriverManager.getConnection(url, username, password);
    }

    public static void main(String[] args) throws IOException {
        try (Connection connection = getConnection()) {
            System.out.println("MySQL connection successful!");
        } catch (SQLException e) {
            System.out.println("MySQL connection failed!");
            System.out.println(e.getMessage());
        }

        slots.put("P01", "Available");
        slots.put("P02", "Available");
        slots.put("P03", "Occupied");
        slots.put("P04", "Available");
        slots.put("P05", "Occupied");

        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);

        // Parking availability API (existing frontend API)
        server.createContext("/api/parking", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"GET method required\"}");
                return;
            }

            String sql = "SELECT slot_number, status FROM parking_slots ORDER BY slot_number";
            StringBuilder json = new StringBuilder("{\"slots\":[");
            int total = 0;
            int available = 0;
            int occupied = 0;

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 var result = statement.executeQuery()) {

                boolean first = true;
                while (result.next()) {
                    String slot = result.getString("slot_number");
                    String status = result.getString("status");

                    if (!first) json.append(",");
                    json.append("{\"slot\":\"").append(jsonEscape(slot))
                        .append("\",\"status\":\"").append(jsonEscape(status)).append("\"}");
                    first = false;
                    total++;

                    if ("Available".equals(status)) available++;
                    else if ("Occupied".equals(status)) occupied++;
                }

                json.append("],\"total\":").append(total)
                    .append(",\"available\":").append(available)
                    .append(",\"occupied\":").append(occupied).append("}");

                sendResponse(exchange, 200, json.toString());
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Unable to load parking slots\"}");
            }
        });

        // Reservation API
        server.createContext("/api/reserve", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"POST method required\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String customerName = form.get("customerName");
            String vehicleNumber = form.get("vehicleNumber");
            String parkingSlot = form.get("parkingSlot");

            if (isBlank(customerName) || isBlank(vehicleNumber) || isBlank(parkingSlot)) {
                sendResponse(exchange, 400, "{\"message\":\"Please fill all fields\"}");
                return;
            }

            String selectSql = "SELECT status FROM parking_slots WHERE slot_number = ?";
            String insertSql = "INSERT INTO reservations (customer_name, vehicle_number, parking_slot) VALUES (?, ?, ?)";
            String occupySql = "UPDATE parking_slots SET status = 'Occupied' WHERE slot_number = ? AND status = 'Available'";

            try (Connection connection = getConnection()) {
                connection.setAutoCommit(false);
                try {
                    String currentStatus = null;
                    try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                        select.setString(1, parkingSlot);
                        try (var result = select.executeQuery()) {
                            if (result.next()) currentStatus = result.getString("status");
                        }
                    }

                    if (currentStatus == null) {
                        connection.rollback();
                        sendResponse(exchange, 400, "{\"message\":\"Invalid parking slot\"}");
                        return;
                    }
                    if (!"Available".equals(currentStatus)) {
                        connection.rollback();
                        sendResponse(exchange, 409, "{\"message\":\"Parking slot is not available\"}");
                        return;
                    }

                    try (PreparedStatement update = connection.prepareStatement(occupySql)) {
                        update.setString(1, parkingSlot);
                        if (update.executeUpdate() != 1) {
                            connection.rollback();
                            sendResponse(exchange, 409, "{\"message\":\"Parking slot is not available\"}");
                            return;
                        }
                    }

                    try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                        insert.setString(1, customerName);
                        insert.setString(2, vehicleNumber);
                        insert.setString(3, parkingSlot);
                        insert.executeUpdate();
                    }

                    connection.commit();
                    synchronized (slots) {
                        slots.put(parkingSlot, "Occupied");
                    }
                    sendResponse(exchange, 200,
                        "{\"message\":\"Reservation successful for " + jsonEscape(parkingSlot) + "!\"}");
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Database error. Check MySQL connection and tables.\"}");
            }
        });

        // Queue token generation API
        server.createContext("/api/queue", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"POST method required\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String customerName = form.get("customerName");
            String vehicleNumber = form.get("vehicleNumber");

            if (isBlank(customerName) || isBlank(vehicleNumber)) {
                sendResponse(exchange, 400, "{\"message\":\"Please fill all fields\"}");
                return;
            }

            String tokenNumber = "Q" + System.currentTimeMillis();
            String sql = "INSERT INTO queue_tokens (customer_name, vehicle_number, token_number, queue_status) VALUES (?, ?, ?, ?)";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, customerName);
                statement.setString(2, vehicleNumber);
                statement.setString(3, tokenNumber);
                statement.setString(4, "Waiting");
                statement.executeUpdate();

                sendResponse(exchange, 200,
                    "{\"message\":\"Queue token generated\",\"tokenNumber\":\"" + tokenNumber + "\",\"status\":\"Waiting\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Unable to save queue token\"}");
            }
        });

        // Check queue status by token
        server.createContext("/api/queue-status", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"GET method required\"}");
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            String tokenNumber = query.get("token");
            if (isBlank(tokenNumber)) {
                sendResponse(exchange, 400, "{\"message\":\"Please enter a token number\"}");
                return;
            }

            String sql = "SELECT token_number, queue_status FROM queue_tokens WHERE token_number = ?";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenNumber);
                try (var result = statement.executeQuery()) {
                    if (result.next()) {
                        sendResponse(exchange, 200,
                            "{\"tokenNumber\":\"" + jsonEscape(result.getString("token_number"))
                            + "\",\"status\":\"" + jsonEscape(result.getString("queue_status")) + "\"}");
                    } else {
                        sendResponse(exchange, 404, "{\"message\":\"Queue token not found\"}");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Database error\"}");
            }
        });

        // Admin: update a parking slot's status
        server.createContext("/api/admin/slot", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"POST method required\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String slotNumber = form.get("slotNumber");
            String status = form.get("status");

            if (isBlank(slotNumber) || isBlank(status)
                    || !slotNumber.matches("P0[1-5]")
                    || !("Available".equals(status) || "Occupied".equals(status))) {
                sendResponse(exchange, 400, "{\"message\":\"Invalid slot or status\"}");
                return;
            }

            String sql = "UPDATE parking_slots SET status = ? WHERE slot_number = ?";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, slotNumber);
                int updated = statement.executeUpdate();

                if (updated > 0) {
                    synchronized (slots) {
                        slots.put(slotNumber, status);
                    }
                    sendResponse(exchange, 200,
                        "{\"message\":\"" + slotNumber + " updated to " + status + "\"}");
                } else {
                    sendResponse(exchange, 404, "{\"message\":\"Parking slot not found\"}");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Database update failed\"}");
            }
        });

        // Admin: get parking slot counts from MySQL
        server.createContext("/api/admin/slots", exchange -> {
            setCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"GET method required\"}");
                return;
            }

            String sql = "SELECT COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN status = 'Available' THEN 1 ELSE 0 END), 0) AS available, "
                + "COALESCE(SUM(CASE WHEN status = 'Occupied' THEN 1 ELSE 0 END), 0) AS occupied "
                + "FROM parking_slots";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 var result = statement.executeQuery()) {
                if (result.next()) {
                    String json = "{\"totalSlots\":" + result.getInt("total")
                        + ",\"availableSlots\":" + result.getInt("available")
                        + ",\"occupiedSlots\":" + result.getInt("occupied") + "}";
                    sendResponse(exchange, 200, json);
                } else {
                    sendResponse(exchange, 500, "{\"message\":\"Unable to calculate parking counts\"}");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"message\":\"Unable to load parking counts\"}");
            }
        });

        // Admin: call the oldest waiting queue token
        server.createContext("/api/admin/call-next", exchange -> {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"POST method required\"}");
                return;
            }

            try (Connection con = getConnection()) {
                con.setAutoCommit(false);
                try {
                    String token = null;
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT token_number FROM queue_tokens "
                          + "WHERE queue_status = 'Waiting' ORDER BY id ASC LIMIT 1 FOR UPDATE")) {
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) token = rs.getString("token_number");
                        }
                    }

                    if (token == null) {
                        con.rollback();
                        sendResponse(exchange, 200,
                                "{\"message\":\"No waiting vehicles in the queue.\"}");
                        return;
                    }

                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE queue_tokens SET queue_status = 'Called' "
                          + "WHERE token_number = ? AND queue_status = 'Waiting'")) {
                        ps.setString(1, token);
                        ps.executeUpdate();
                    }
                    con.commit();
                    sendResponse(exchange, 200,
                            "{\"message\":\"Next vehicle called.\",\"tokenNumber\":\""
                            + jsonEscape(token) + "\"}");
                } catch (Exception e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500,
                        "{\"message\":\"Unable to call next vehicle.\"}");
            }
        });

        // Admin: complete a reservation and release its slot when no other confirmed booking uses it
        server.createContext("/api/admin/complete-booking", exchange -> {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"message\":\"POST method required\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String idText = form.get("bookingId");
            if (isBlank(idText) || !idText.matches("\\d+")) {
                sendResponse(exchange, 400, "{\"message\":\"Enter a valid Booking ID\"}");
                return;
            }

            try (Connection con = getConnection()) {
                con.setAutoCommit(false);
                try {
                    String slot = null;
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT parking_slot FROM reservations "
                          + "WHERE id = ? AND booking_status = 'Confirmed' FOR UPDATE")) {
                        ps.setInt(1, Integer.parseInt(idText));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) slot = rs.getString("parking_slot");
                        }
                    }

                    if (slot == null) {
                        con.rollback();
                        sendResponse(exchange, 404,
                                "{\"message\":\"Confirmed booking not found\"}");
                        return;
                    }

                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE reservations SET booking_status = 'Completed' "
                          + "WHERE id = ? AND booking_status = 'Confirmed'")) {
                        ps.setInt(1, Integer.parseInt(idText));
                        if (ps.executeUpdate() != 1) {
                            con.rollback();
                            sendResponse(exchange, 409,
                                    "{\"message\":\"Booking status changed; please refresh\"}");
                            return;
                        }
                    }

                    boolean anotherConfirmedBooking = false;
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT COUNT(*) AS active_count FROM reservations "
                          + "WHERE parking_slot = ? AND booking_status = 'Confirmed'")) {
                        ps.setString(1, slot);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) anotherConfirmedBooking = rs.getInt("active_count") > 0;
                        }
                    }

                    if (!anotherConfirmedBooking) {
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE parking_slots SET status = 'Available' WHERE slot_number = ?")) {
                            ps.setString(1, slot);
                            ps.executeUpdate();
                        }
                    }

                    con.commit();
                    String msg = anotherConfirmedBooking
                            ? "Booking completed. Another confirmed booking still uses slot " + slot + "."
                            : "Booking completed. Slot " + slot + " is now available.";
                    sendResponse(exchange, 200, "{\"message\":\"" + jsonEscape(msg) + "\"}");
                } catch (Exception e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500,
                        "{\"message\":\"Unable to complete booking\"}");
            }
        });

        // Register every endpoint before starting the server.
        server.setExecutor(null);
        server.start();

        System.out.println("Smart Parking Server started!");
        System.out.println("Parking API: http://localhost:8081/api/parking");
        System.out.println("Reservation API: http://localhost:8081/api/reserve");
        System.out.println("Queue API: http://localhost:8081/api/queue");
        System.out.println("Queue status API: http://localhost:8081/api/queue-status?token=YOUR_TOKEN");
        System.out.println("Admin slot update API: http://localhost:8081/api/admin/slot");
        System.out.println("Admin counts API: http://localhost:8081/api/admin/slots");
        System.out.println("Call next API: http://localhost:8081/api/admin/call-next (POST)");
        System.out.println("Complete booking API: http://localhost:8081/api/admin/complete-booking (POST)");
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response)
            throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) return form;

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                : "";
            form.put(key, value);
        }
        return form;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
