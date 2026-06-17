import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input  = sc.nextLine().trim();
            String[] parts = input.split("\\s+");

            if (parts[0].equals("echo")) {
                for (int i = 1; i < parts.length; i++) {
                    System.out.print(parts[i]);
                    if (i < parts.length - 1) {
                        System.out.print(" ");
                    }
                }
                System.out.println();
            }


            else if (parts[0].equals("type")) {
                String cmd = parts[1];
                if (cmd.equals("echo") 
                    || cmd.equals("exit") 
                    || cmd.equals("type") 
                    || cmd.equals("pwd"))
                    System.out.println(cmd + " is a shell builtin");
                else {
                    String executable = findExecutable(cmd);
                    
                    if (executable != null) System.out.println(cmd + " is " + executable);
                    else System.out.println(cmd + ": not found");
                }
            }

            else if (parts[0].equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            }

            else if (parts[0].equals("exit")) {
                break;
            }

            else {
                String executable = findExecutable(parts[0]);

                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);

                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                }
                else System.out.println(parts[0] + ": command not found");
            }
        }
        sc.close();
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        String[] directories = pathEnv.split(File.pathSeparator);

        for (String dir : directories) {
            File file = new File(dir, command);

            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }
}
