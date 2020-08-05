package sg.edu.appventure.examclock.connection;

import io.socket.client.IO;
import io.socket.client.Socket;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import okhttp3.OkHttpClient;
import sg.edu.appventure.examclock.MainController;
import sg.edu.appventure.examclock.PreferenceController;
import sg.edu.appventure.examclock.model.Exam;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class ClientSocket {
    private Socket socket;
    private final MainController controller;
    public static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter[] timeFormatters = {
            DateTimeFormatter.ofPattern("hh:mma"),
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("h:mma"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("hha"),
            DateTimeFormatter.ofPattern("ha")
    };

    private static LocalTime parseTime(String time, int index) {
        if (index >= timeFormatters.length) throw new DateTimeParseException("Invalid date/time!", time, 0);
        try {
            return LocalTime.parse(time.replace(" ", ""), timeFormatters[index]);
        } catch (DateTimeException e) {
            return parseTime(time, index + 1);
        }
    }

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
                Platform.runLater(() -> PreferenceController.connectivityStateProperty.set("Connected"));
                identifySelf();
            }).on(Socket.EVENT_CONNECT_TIMEOUT, args -> Platform.runLater(() -> PreferenceController.connectivityStateProperty.set("Connection time out")))
                    .on(Socket.EVENT_RECONNECTING, args -> Platform.runLater(() -> PreferenceController.connectivityStateProperty.set("Reconnecting")))
                    .on(Socket.EVENT_DISCONNECT, args -> Platform.runLater(() -> PreferenceController.connectivityStateProperty.set("Disconnected")));
            socket.on("clock_id_clash", this::onClockIDClash);
            socket.on("new_exam", this::onNewExam);
            socket.on("edit_exam", this::onEditExam);
            socket.on("delete_exam", this::onDeleteExam);
            socket.on("toilet", this::onToilet);
            socket.on("request", this::onRequest);
            controller.exams.addListener((ListChangeListener<Exam>) c -> {
                while (c.next()) {
                    c.getAddedSubList().forEach(exam -> {
                        JSONObject examObj = new JSONObject();
                        examObj.put("id", exam.id);
                        examObj.put("name", exam.name);
                        examObj.put("date", exam.getDate());
                        examObj.put("start", exam.getStart());
                        examObj.put("end", exam.getEnd());
                        socket.emit("new_exam", examObj.toJSONString());
                    });
                    c.getRemoved().forEach(exam -> socket.emit("delete_exam", exam.id));
                }
            });
            controller.toiletOccupied.addListener((observable, oldValue, newValue) -> socket.emit("toilet", newValue ? "occupied" : "vacant"));
            socket.open();
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private void onToilet(Object object) {
        Platform.runLater(() -> controller.toiletOccupied.set(!controller.toiletOccupied.get()));
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
        JSONObject obj = new JSONObject();
        obj.put("clockID", PreferenceController.clockID);
        obj.put("clockName", PreferenceController.lanNameProperty.get());
        JSONArray array = new JSONArray();
        array.addAll(controller.exams);
        obj.put("exams", array);
        return obj.toJSONString();
    }

    public void onClockIDClash(Object... args) {
        System.out.println("So somehow there's a clash of clock id");
        controller.regenClockID();
        identifySelf();
    }

    public void close() {
        socket.close();
    }

    public Socket getSocket() {
        return socket;
    }

    public void resend() {
        JSONObject obj = new JSONObject();
        JSONArray array = new JSONArray();
        array.addAll(controller.exams);
        obj.put("exams", array);
        socket.emit("exam_", obj.toJSONString());
    }
}
