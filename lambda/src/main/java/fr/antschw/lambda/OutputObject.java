package fr.antschw.lambda;

public class OutputObject {

    private String message;
    private String requestId;

    public OutputObject() {
    }

    public OutputObject(String message, String requestId) {
        this.message = message;
        this.requestId = requestId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
