package app.nush.examclock.connection;

import app.nush.examclock.controllers.AddExamController;
import app.nush.examclock.controllers.MainController;
import app.nush.examclock.controllers.PreferenceController;
import app.nush.examclock.model.Exam;
import com.google.gson.JsonObject;
import io.socket.client.IO;
import io.socket.client.Socket;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import okhttp3.OkHttpClient;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The type Client socket.
 */
public class ClientSocket {

    /**
     * The connectivity state property. Changes when connection state changes
     */
    public static final SimpleStringProperty connectivityStateProperty = new SimpleStringProperty("Not connected");
    /**
     * The constant dateFormatter used for date data transfer (machine friendly, use just numbers)
     */
    public static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final MainController controller;
    private Socket socket;

    /**
     * Instantiates a new Client socket.
     *
     * @param controller the controller
     */
    public ClientSocket(MainController controller) {
        this.controller = controller;
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        IO.setDefaultOkHttpWebSocketFactory(okHttpClient);
        IO.setDefaultOkHttpCallFactory(okHttpClient);
        IO.Options opts = new IO.Options();
        opts.path = "/socket.clocks";
        opts.callFactory = okHttpClient;
        opts.webSocketFactory = okHttpClient;
        try {
            opts.query = "clock=" + URLEncoder.encode(identifySelf(), String.valueOf(StandardCharsets.UTF_8));
            socket = IO.socket("https://exam-clock-nush.tk", opts);
//            socket = IO.socket("http://localhost:3000", opts);
            socket.on(Socket.EVENT_CONNECT, args -> {
                System.out.println("Connected to Server!");
                Platform.runLater(() -> connectivityStateProperty.set("Connected"));
            }).on(Socket.EVENT_CONNECT_TIMEOUT, args -> Platform.runLater(() -> connectivityStateProperty.set("Connection time out")))
                    .on(Socket.EVENT_RECONNECTING, args -> Platform.runLater(() -> connectivityStateProperty.set("Reconnecting")))
                    .on(Socket.EVENT_DISCONNECT, args -> Platform.runLater(() -> connectivityStateProperty.set("Disconnected")));
            socket.on("clock_id_clash", this::onClockIDClash);
            socket.on("new_exam", this::onNewExam);
            socket.on("edit_exam", this::onEditExam);
            socket.on("delete_exam", this::onDeleteExam);
            socket.on("toilet", this::onToilet);
            socket.on("request", this::onRequest);
            controller.exams.addListener((ListChangeListener<Exam>) c -> {
                while (c.next()) {
                    c.getAddedSubList().forEach(exam -> socket.emit("new_exam", MainController.gson.toJson(exam)));
                    c.getRemoved().forEach(exam -> socket.emit("delete_exam", exam.id));
                }
            });
            PreferenceController.nameProperty.addListener(((observable, oldValue, newValue) -> socket.emit("rename", newValue)));
            controller.toiletMaleOccupied.addListener((observable, oldValue, newValue) -> socket.emit("toilet", newValue ? "occupied" : "vacant", "male"));
            controller.toiletFemaleOccupied.addListener((observable, oldValue, newValue) -> socket.emit("toilet", newValue ? "occupied" : "vacant", "female"));
            socket.open();
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private static LocalTime parseTime(String time, int index) {
        if (index >= AddExamController.timeFormatters.length)
            throw new DateTimeParseException("Invalid date/time!", time, 0);
        try {
            return LocalTime.parse(time.replace(" ", ""), AddExamController.timeFormatters[index]);
        } catch (DateTimeException e) {
            return parseTime(time, index + 1);
        }
    }

    private void onToilet(Object[] objects) {
        String gender = String.valueOf(objects[1]);
        if (gender.equalsIgnoreCase("male"))
            Platform.runLater(() -> controller.toiletMaleOccupied.set(!controller.toiletMaleOccupied.get()));
        else if (gender.equalsIgnoreCase("female"))
            Platform.runLater(() -> controller.toiletFemaleOccupied.set(!controller.toiletFemaleOccupied.get()));
    }

    private void onRequest(Object[] objects) {
        String socketID = String.valueOf(objects[0]);
        String nick = String.valueOf(objects[1]);
        if (PreferenceController.openToRequestsProperty.get()) {
            System.out.println("Received request from " + socketID + " (" + nick + ") to take control");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "A new controller \"" + nick + "\" wants to connect, confirm?", ButtonType.OK, ButtonType.NO);
                alert.showAndWait();
                if (alert.getResult() == ButtonType.OK) socket.emit("request_callback", socketID, "accepted");
                else socket.emit("request_callback", socketID, "rejected");
                System.out.println("Responded to " + socketID + " (" + nick + ") with " + (alert.getResult() == ButtonType.OK ? "accepted" : "rejected"));
            });
        } else socket.emit("request_callback", socketID, "rejected");
    }

    private void onNewExam(Object... objects) {
        try {
            Exam exam = new Exam(
                    String.valueOf(objects[1]),
                    LocalDate.parse(String.valueOf(objects[2]), dateFormatter),
                    parseTime(String.valueOf(objects[3]), 0),
                    parseTime(String.valueOf(objects[4]), 0));
            Platform.runLater(() -> controller.exams.add(exam));
        } catch (DateTimeException e) {
            e.printStackTrace();
            socket.emit("clock_error", objects[0], "date_time_invalid");
        } catch (RuntimeException e) {
            e.printStackTrace();
            socket.emit("clock_error", objects[0], e.getMessage());
        }
    }

    private void onEditExam(Object... objects) {
        try {
            for (Exam exam : controller.exams) {
                if (exam.id.equals(String.valueOf(objects[1]))) {
                    exam.name = String.valueOf(objects[2]);
                    exam.date = String.valueOf(objects[3]);
                    exam.start = String.valueOf(objects[4]);
                    exam.end = String.valueOf(objects[5]);
                    Platform.runLater(() -> controller.getExamHolder(exam).setExam(exam));
                    break;
                }
            }
        } catch (DateTimeException e) {
            e.printStackTrace();
            socket.emit("clock_error", objects[0], "date_time_invalid");
        } catch (RuntimeException e) {
            e.printStackTrace();
            socket.emit("clock_error", objects[0], e.getMessage());
        }
    }

    private void onDeleteExam(Object... objects) {
        String id = String.valueOf(objects[1]);
        Platform.runLater(() -> {
            for (int i = 0; i < controller.exams.size(); i++) {
                if (controller.exams.get(i).id.equals(id)) {
                    controller.exams.remove(i);
                    return;
                }
            }
            socket.emit("clock_error", objects[0], "exam_not_found");
        });
    }

    private String identifySelf() {
        JsonObject obj = new JsonObject();
        obj.addProperty("clockID", PreferenceController.clockID);
        obj.addProperty("clockName", PreferenceController.nameProperty.get());
        obj.add("exams", MainController.gson.toJsonTree(controller.exams));
        return obj.toString();
    }

    private void onClockIDClash(Object... args) {
        System.out.println("So somehow there's a clash of clock id");
        controller.regenClockID();
        socket.emit("clock_id_un_clash", identifySelf());
    }

    /**
     * Gets socket
     *
     * @return the socket
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Updates the server's exam cache, do this if you encounter desync
     */
    public void forceExamUpdate() {
        socket.emit("exam_update", MainController.gson.toJsonTree(controller.exams));
    }
}
