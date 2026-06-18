import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));

        while (true) {
            System.out.print("$ ");
            if (!sc.hasNextLine()) {
                break;
            }
            String input = sc.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            String[] rawParts = parseInput(input);
            if (rawParts.length == 0) {
                continue;
            }

            // Extract redirection operators
            List<String> cleanArgs = new ArrayList<>();
            String stdoutFile = null;
            String stderrFile = null;
            boolean stdoutAppend = false;
            boolean stderrAppend = false;

            for (int i = 0; i < rawParts.length; i++) {
                if (rawParts[i].equals(">") || rawParts[i].equals("1>")) {
                    if (i + 1 < rawParts.length) {
                        stdoutFile = rawParts[i + 1];
                        i++;
                    }
                } else if (rawParts[i].equals(">>") || rawParts[i].equals("1>>")) {
                    if (i + 1 < rawParts.length) {
                        stdoutFile = rawParts[i + 1];
                        stdoutAppend = true;
                        i++;
                    }
                } else if (rawParts[i].equals("2>")) {
                    if (i + 1 < rawParts.length) {
                        stderrFile = rawParts[i + 1];
                        i++;
                    }
                } else if (rawParts[i].equals("2>>")) {
                    if (i + 1 < rawParts.length) {
                        stderrFile = rawParts[i + 1];
                        stderrAppend = true;
                        i++;
                    }
                } else {
                    cleanArgs.add(rawParts[i]);
                }
            }

            if (cleanArgs.isEmpty()) {
                continue;
            }

            String[] parts = cleanArgs.toArray(new String[0]);

            // Set up streams for built-in commands
            PrintStream outStream = System.out;
            PrintStream errStream = System.err;
            FileOutputStream outFos = null;
            FileOutputStream errFos = null;

            try {
                if (stdoutFile != null) {
                    File outFile = new File(stdoutFile);
                    if (!outFile.isAbsolute()) {
                        outFile = new File(currentDirectory, stdoutFile);
                    }
                    if (outFile.getParentFile() != null) {
                        outFile.getParentFile().mkdirs();
                    }
                    outFos = new FileOutputStream(outFile, stdoutAppend);
                    outStream = new PrintStream(outFos);
                }

                if (stderrFile != null) {
                    File errFile = new File(stderrFile);
                    if (!errFile.isAbsolute()) {
                        errFile = new File(currentDirectory, stderrFile);
                    }
                    if (errFile.getParentFile() != null) {
                        errFile.getParentFile().mkdirs();
                    }
                    errFos = new FileOutputStream(errFile, stderrAppend);
                    errStream = new PrintStream(errFos);
                }

                // Execute command
                if (parts[0].equals("echo")) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) {
                        sb.append(parts[i]);
                        if (i < parts.length - 1) {
                            sb.append(" ");
                        }
                    }
                    outStream.println(sb.toString());
                } 
                
                else if (parts[0].equals("type")) {
                    String cmd = parts[1];
                    if (cmd.equals("echo") 
                        || cmd.equals("exit") 
                        || cmd.equals("type") 
                        || cmd.equals("pwd")) {
                        outStream.println(cmd + " is a shell builtin");
                    } else {
                        String executable = findExecutable(cmd);
                        if (executable != null) {
                            outStream.println(cmd + " is " + executable);
                        } else {
                            outStream.println(cmd + ": not found");
                        }
                    }
                } 
                
                else if (parts[0].equals("cd")) {
                    File target;
                    if (parts[1].equals("~")) {
                        target = new File(System.getenv("HOME"));
                    } else if (parts[1].startsWith("/")) {
                        target = new File(parts[1]);
                    } else {
                        target = new File(currentDirectory, parts[1]);
                    }

                    target = target.getCanonicalFile();

                    if (target.exists() && target.isDirectory()) {
                        currentDirectory = target;
                    } else {
                        errStream.println("cd: " + parts[1] + ": No such file or directory");
                    }
                } 
                
                else if (parts[0].equals("pwd")) {
                    outStream.println(currentDirectory.getAbsolutePath());
                } 
                
                else if (parts[0].equals("exit")) {
                    break;
                } 
                
                else {
                    // External Commands
                    String executable = findExecutable(parts[0]);

                    if (executable != null) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(currentDirectory);

                        if (stdoutFile != null) {
                            File outFile = new File(stdoutFile);
                            if (!outFile.isAbsolute()) {
                                outFile = new File(currentDirectory, stdoutFile);
                            }
                            if (stdoutAppend) {
                                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                            } else {
                                pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
                            }
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (stderrFile != null) {
                            File errFile = new File(stderrFile);
                            if (!errFile.isAbsolute()) {
                                errFile = new File(currentDirectory, stderrFile);
                            }
                            if (stderrAppend) {
                                pb.redirectError(ProcessBuilder.Redirect.appendTo(errFile));
                            } else {
                                pb.redirectError(ProcessBuilder.Redirect.to(errFile));
                            }
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process process = pb.start();
                        process.waitFor();
                    } else {
                        errStream.println(parts[0] + ": command not found");
                    }
                }
            } finally {
                // Ensure output streams are safely closed and flushed
                if (outFos != null) {
                    outFos.close();
                }
                if (errFos != null) {
                    errFos.close();
                }
            }
        }
        sc.close();
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
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

            if (inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '\\' || next == '"' || next == '$') {
                        current.append(next);
                        i++;
                        continue;
                    }
                }
                current.append(c);
                continue;
            }
            
            if (!inSingleQuotes && !inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens.toArray(new String[0]);
    }
}