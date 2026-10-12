package coursesweep;

/** Sends account emails. Swap in an SMTP implementation for real delivery. */
interface Mailer {

    void sendPasswordReset(String toEmail, String resetLink);

    /** Development stand-in: prints the message to the server console instead of emailing it. */
    final class Console implements Mailer {
        @Override
        public void sendPasswordReset(String toEmail, String resetLink) {
            System.out.println();
            System.out.println("[mail] To: " + toEmail);
            System.out.println("[mail] Reset your CourseSweep password (link valid for "
                    + AuthService.RESET_MINUTES + " minutes):");
            System.out.println("[mail] " + resetLink);
            System.out.println();
        }
    }
}
