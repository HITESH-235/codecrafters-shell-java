import java.util.*;
import java.io.*;

public class Main {
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");
    private static File currentDirectory;
    private static boolean isTty = false;
    private static BufferedReader fallbackReader = null;
    private static TreeMap<Integer, Job> backgroundJobs = new TreeMap<>();

    static {
        // Detect if standard input/output are attached to an interactive terminal console
        isTty = (System.console() != null);
    }

    static class Job {
        int id;
        long pid;
        String commandString;
        List<Process> processes;
        List<Thread> threads;
        List<StreamCopier> copiers;

        Job(int id, long pid, String cmd, List<Process> p, List<Thread> t, List<StreamCopier> c) {
            this.id = id;
            this.pid = pid;
            this.commandString = cmd;
            this.processes = p;
            this.threads = t;
            this.copiers = c;
        }

        boolean isDone() {
            for (Process p : processes) if (p.isAlive()) return false;
            for (Thread t : threads) if (t.isAlive()) return false;
            for (StreamCopier c : copiers) if (c.isAlive()) return false;
            return true;
        }
    }

    static class CommandSegment {
        String[] args;
        String stdoutFile;
        String stderrFile;
        boolean stdoutAppend;
        boolean stderrAppend;
    }

    public static void main(String[] args) throws Exception {
        currentDirectory = new File(System.getProperty("user.dir")).getCanonicalFile();
        Reader reader = new InputStreamReader(System.in);

        while (true) {
            reapJobsBeforePrompt();

            System.out.print("$ ");
            System.out.flush();

            String input = readLine(reader);
            if (input == null) break;
            
            input = input.trim();
            if (input.isEmpty()) continue;

            String originalInput = input;

            String[] rawParts = parseInput(input);
            if (rawParts.length == 0) continue;

            // Detect background execution
            boolean isBackground = false;
            if (rawParts[rawParts.length - 1].equals("&")) {
                isBackground = true;
                rawParts = Arrays.copyOfRange(rawParts, 0, rawParts.length - 1);
            }

            List<CommandSegment> segments = parseSegments(rawParts);
            if (segments.isEmpty()) continue;

            // Fast-path: Single synchronous builtin
            if (segments.size() == 1 && isBuiltin(segments.get(0).args[0]) && !isBackground) {
                executeBuiltinSync(segments.get(0));
                continue;
            }

            // Pipeline / Async Execution
            List<Process> procs = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            List<StreamCopier> copiers = new ArrayList<>();

            InputStream currentIn = System.in;

            for (int i = 0; i < segments.size(); i++) {
                CommandSegment seg = segments.get(i);
                boolean isLast = (i == segments.size() - 1);

                PipedOutputStream currentOut = null;
                PipedInputStream nextIn = null;

                if (!isLast) {
                    currentOut = new PipedOutputStream();
                    nextIn = new PipedInputStream(currentOut);
                }

                OutputStream actualOut = createActualOut(seg, isLast ? System.out : currentOut);
                OutputStream actualErr = createActualErr(seg, System.err);

                if (isBuiltin(seg.args[0])) {
                    final OutputStream threadOut = actualOut;
                    final OutputStream threadErr = actualErr;
                    final InputStream threadIn = currentIn;
                    
                    Thread t = new Thread(() -> {
                        try {
                            runBuiltin(seg, threadIn, threadOut, threadErr);
                        } finally {
                            if (threadOut != System.out && threadOut != System.err) try { threadOut.close(); } catch (Exception e) {}
                            if (threadErr != System.err && threadErr != System.out) try { threadErr.close(); } catch (Exception e) {}
                        }
                    });
                    t.start();
                    threads.add(t);
                } else {
                    String executable = findExecutable(seg.args[0]);
                    if (executable == null) {
                        System.err.println(seg.args[0] + ": command not found");
                        if (actualOut != System.out && actualOut != System.err) try { actualOut.close(); } catch (Exception e) {}
                        if (actualErr != System.err && actualErr != System.out) try { actualErr.close(); } catch (Exception e) {}
                        currentIn = nextIn;
                        continue;
                    }

                    ProcessBuilder pb = new ProcessBuilder(seg.args);
                    pb.directory(currentDirectory);

                    // Map I/O securely
                    if (currentIn == System.in) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    else pb.redirectInput(ProcessBuilder.Redirect.PIPE);

                    if (actualOut == System.out) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    else if (actualOut instanceof FileOutputStream) {
                        pb.redirectOutput(seg.stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(seg.stdoutFile)) : ProcessBuilder.Redirect.to(new File(seg.stdoutFile)));
                    } else pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                    if (actualErr == System.err) pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    else if (actualErr instanceof FileOutputStream) {
                        pb.redirectError(seg.stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(seg.stderrFile)) : ProcessBuilder.Redirect.to(new File(seg.stderrFile)));
                    } else pb.redirectError(ProcessBuilder.Redirect.PIPE);

                    Process p = pb.start();
                    procs.add(p);

                    // Wire pipes 
                    if (currentIn != System.in) {
                        StreamCopier sc = new StreamCopier(currentIn, p.getOutputStream(), true);
                        sc.start();
                        copiers.add(sc);
                    }
                    if (actualOut != System.out && !(actualOut instanceof FileOutputStream)) {
                        StreamCopier sc = new StreamCopier(p.getInputStream(), actualOut, true);
                        sc.start();
                        copiers.add(sc);
                    }
                    if (actualErr != System.err && !(actualErr instanceof FileOutputStream)) {
                        StreamCopier sc = new StreamCopier(p.getErrorStream(), actualErr, true);
                        sc.start();
                        copiers.add(sc);
                    }
                }
                currentIn = nextIn;
            }

            if (isBackground) {
                int jobId = 1;
                while (backgroundJobs.containsKey(jobId)) jobId++;
                long pid = procs.isEmpty() ? 0 : procs.get(procs.size() - 1).pid();
                System.out.println("[" + jobId + "] " + pid);
                backgroundJobs.put(jobId, new Job(jobId, pid, originalInput, procs, threads, copiers));
            } else {
                for (Process p : procs) p.waitFor();
                for (Thread t : threads) t.join();
                for (StreamCopier sc : copiers) sc.join();
            }
        }
    }

    private static void reapJobsBeforePrompt() {
        List<Integer> done = new ArrayList<>();
        for (Job j : backgroundJobs.values()) {
            if (j.isDone()) done.add(j.id);
        }
        for (int id : done) {
            Job j = backgroundJobs.remove(id);
            System.out.println("[" + j.id + "]+  Done                    " + j.commandString);
        }
    }

    private static boolean isBuiltin(String command) {
        return BUILTINS.contains(command);
    }

    private static void executeBuiltinSync(CommandSegment seg) throws Exception {
        OutputStream actualOut = createActualOut(seg, System.out);
        OutputStream actualErr = createActualErr(seg, System.err);

        try {
            runBuiltin(seg, System.in, actualOut, actualErr);
        } finally {
            if (actualOut != System.out && actualOut != System.err) actualOut.close();
            if (actualErr != System.err && actualErr != System.out) actualErr.close();
        }
    }

    private static void runBuiltin(CommandSegment seg, InputStream in, OutputStream out, OutputStream err) {
        PrintStream outStream = new PrintStream(out, true);
        PrintStream errStream = new PrintStream(err, true);
        String cmd = seg.args[0];

        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < seg.args.length; i++) {
                sb.append(seg.args[i]);
                if (i < seg.args.length - 1) sb.append(" ");
            }
            outStream.println(sb.toString());
        } 
        else if (cmd.equals("type")) {
            if (seg.args.length < 2) return;
            String arg = seg.args[1];
            if (isBuiltin(arg)) {
                outStream.println(arg + " is a shell builtin");
            } else {
                String executable = findExecutable(arg);
                if (executable != null) outStream.println(arg + " is " + executable);
                else outStream.println(arg + ": not found");
            }
        } 
        else if (cmd.equals("pwd")) {
            outStream.println(currentDirectory.getAbsolutePath());
        } 
        else if (cmd.equals("cd")) {
            if (seg.args.length < 2) return;
            String path = seg.args[1];
            File target;
            if (path.equals("~")) {
                target = new File(System.getenv("HOME"));
            } else if (path.startsWith("/")) {
                target = new File(path);
            } else {
                target = new File(currentDirectory, path);
            }
            
            try { target = target.getCanonicalFile(); } catch (IOException e) {}

            if (target.exists() && target.isDirectory()) {
                currentDirectory = target;
            } else {
                errStream.println("cd: " + path + ": No such file or directory");
            }
        } 
        else if (cmd.equals("exit")) {
            System.exit(0);
        }
        else if (cmd.equals("jobs")) {
            List<Integer> done = new ArrayList<>();
            if (seg.args.length > 1) { 
                try {
                    int targetId = Integer.parseInt(seg.args[1]);
                    Job j = backgroundJobs.get(targetId);
                    if (j != null) {
                        if (j.isDone()) {
                            outStream.println("[" + j.id + "]+  Done                    " + j.commandString);
                            done.add(j.id);
                        } else outStream.println("[" + j.id + "]+  Running                 " + j.commandString);
                    }
                } catch (Exception e) {}
            } else {
                for (Job j : backgroundJobs.values()) {
                    if (j.isDone()) {
                        outStream.println("[" + j.id + "]+  Done                    " + j.commandString);
                        done.add(j.id);
                    } else outStream.println("[" + j.id + "]+  Running                 " + j.commandString);
                }
            }
            for (int id : done) backgroundJobs.remove(id);
        }
    }

    private static OutputStream createActualOut(CommandSegment seg, OutputStream def) throws IOException {
        if (seg.stdoutFile != null) {
            File f = new File(seg.stdoutFile);
            if (!f.isAbsolute()) f = new File(currentDirectory, seg.stdoutFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            return new FileOutputStream(f, seg.stdoutAppend);
        }
        return def;
    }

    private static OutputStream createActualErr(CommandSegment seg, OutputStream def) throws IOException {
        if (seg.stderrFile != null) {
            File f = new File(seg.stderrFile);
            if (!f.isAbsolute()) f = new File(currentDirectory, seg.stderrFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            return new FileOutputStream(f, seg.stderrAppend);
        }
        return def;
    }

    static class StreamCopier extends Thread {
        InputStream in; OutputStream out; boolean closeOut;
        StreamCopier(InputStream in, OutputStream out, boolean closeOut) {
            this.in = in; this.out = out; this.closeOut = closeOut;
        }
        public void run() {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
            } finally {
                if (closeOut && out != System.out && out != System.err) {
                    try { out.close(); } catch(Exception e) {}
                }
            }
        }
    }

    private static List<CommandSegment> parseSegments(String[] parts) {
        List<CommandSegment> segments = new ArrayList<>();
        CommandSegment currentSeg = new CommandSegment();
        List<String> currentArgs = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            String token = parts[i];
            if (token.equals("|")) {
                if (!currentArgs.isEmpty()) {
                    currentSeg.args = currentArgs.toArray(new String[0]);
                    segments.add(currentSeg);
                }
                currentSeg = new CommandSegment();
                currentArgs.clear();
            } else if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < parts.length) currentSeg.stdoutFile = parts[++i];
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < parts.length) {
                    currentSeg.stdoutFile = parts[++i];
                    currentSeg.stdoutAppend = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < parts.length) currentSeg.stderrFile = parts[++i];
            } else if (token.equals("2>>")) {
                if (i + 1 < parts.length) {
                    currentSeg.stderrFile = parts[++i];
                    currentSeg.stderrAppend = true;
                }
            } else {
                currentArgs.add(token);
            }
        }
        if (!currentArgs.isEmpty()) {
            currentSeg.args = currentArgs.toArray(new String[0]);
            segments.add(currentSeg);
        }
        return segments;
    }

    private static String readLine(Reader reader) throws Exception {
        if (isTty) {
            return readLineWithAutocomplete(reader);
        } else {
            if (fallbackReader == null) fallbackReader = new BufferedReader(new InputStreamReader(System.in));
            return fallbackReader.readLine();
        }
    }

    private static List<String> getCompletions(String prefix) {
        Set<String> matches = new TreeSet<>();
        
        // Check Builtins
        for (String builtin : BUILTINS) {
            if (builtin.startsWith(prefix)) {
                matches.add(builtin);
            }
        }

        // Check Executables in PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dirPath : directories) {
                File dir = new File(dirPath);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) -> name.startsWith(prefix));
                    if (files != null) {
                        for (File file : files) {
                            if (file.canExecute() && !file.isDirectory()) {
                                matches.add(file.getName());
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(matches);
    }

    private static String readLineWithAutocomplete(Reader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        setTerminalRaw(true);

        try {
            while (true) {
                int code = reader.read();
                if (code == -1) {
                    if (sb.length() == 0) return null;
                    break;
                }

                char c = (char) code;

                if (c == '\n' || c == '\r') {
                    System.out.print("\r\n");
                    System.out.flush();
                    break;
                } else if (code == 9) { // TAB Key
                    String currentInput = sb.toString();
                    
                    if (!currentInput.contains(" ") && !currentInput.isEmpty()) {
                        List<String> matches = getCompletions(currentInput);

                        if (matches.size() == 1) {
                            // Exact single match - autocomplete with trailing space
                            String completion = matches.get(0).substring(currentInput.length()) + " ";
                            sb.append(completion);
                            System.out.print(completion);
                            System.out.flush();
                        } else if (matches.size() > 1) {
                            // Multiple matches - find longest common prefix
                            String commonPrefix = matches.get(0);
                            for (int i = 1; i < matches.size(); i++) {
                                String match = matches.get(i);
                                int j = 0;
                                while (j < commonPrefix.length() && j < match.length() && commonPrefix.charAt(j) == match.charAt(j)) {
                                    j++;
                                }
                                commonPrefix = commonPrefix.substring(0, j);
                            }

                            if (commonPrefix.length() > currentInput.length()) {
                                // Auto-fill the common part and beep
                                String completion = commonPrefix.substring(currentInput.length());
                                sb.append(completion);
                                System.out.print(completion);
                                System.out.flush();
                                System.out.print((char) 7);
                                System.out.flush();
                            } else {
                                // No further common prefix to autofill, just beep
                                System.out.print((char) 7);
                                System.out.flush();
                            }
                        } else {
                            // No matches at all, ring bell
                            System.out.print((char) 7);
                            System.out.flush();
                        }
                    } else {
                        System.out.print((char) 7);
                        System.out.flush();
                    }
                } else if (code == 127 || code == 8) { // Backspace
                    if (sb.length() > 0) {
                        sb.deleteCharAt(sb.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                } else {
                    sb.append(c);
                    System.out.print(c);
                    System.out.flush();
                }
            }
        } finally {
            setTerminalRaw(false);
        }

        return sb.toString();
    }

    private static void setTerminalRaw(boolean raw) {
        try {
            String[] cmd = {"/bin/sh", "-c", raw ? "stty -icanon -echo < /dev/tty" : "stty icanon echo < /dev/tty"};
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
        } catch (Exception e) {}
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] directories = pathEnv.split(File.pathSeparator);

        for (String dir : directories) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) return file.getAbsolutePath();
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

        if (current.length() > 0) tokens.add(current.toString());

        return tokens.toArray(new String[0]);
    }
}