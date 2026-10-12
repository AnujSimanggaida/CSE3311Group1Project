package coursesweep;

/** An error that is safe to show to the client, with the HTTP status to send. */
final class ApiException extends RuntimeException {

    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
