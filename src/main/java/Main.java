import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        File currentDirectory = new File(System.getProperty("user.dir"));

        while (true) {
            System.out.print("$ ");
            String input  = sc.nextLine().trim();
            String[] parts = parseInput(input);

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

            else if (parts[0].equals("cd")) {
                File target;

                if (parts[1].equals("~")) {
                    target = new File(System.getenv("HOME"));
                }
                else if (parts[1].startsWith("/")) {
                    target = new File(parts[1]);
                } else {
                    target = new File(currentDirectory, parts[1]);
                }

                target = target.getCanonicalFile();

                if (target.exists() && target.isDirectory()) {
                    currentDirectory = target;
                } else {
                    System.out.println(
                        "cd: " + parts[1] + ": No such file or directory"
                    );
                }
            }

            else if (parts[0].equals("pwd")) {
                System.out.println(currentDirectory.getAbsolutePath());
            }

            else if (parts[0].equals("exit")) break;

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

    private static String[] parseInput(String input) {
        List<String> tokens = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            }

            else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            }

            else if (
                Character.isWhitespace(c)
                && !inSingleQuotes
                && !inDoubleQuotes
            ) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }

            else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens.toArray(new String[0]);
    }
}