import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input  = sc.nextLine().trim();
            String[] parts = input.split(" ");

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
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type")) 
                    System.out.println(cmd + " is a shell builtin");
                else {
                    String pathEnv = System.getenv("PATH");
                    String[] directories = pathEnv.split(File.pathSeparator);
    
                    boolean found = false;
                    for (String dir : directories) {
                        File file = new File(dir, cmd);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(cmd+": not found");
                    }
                }
            }

            else if (parts[0].equals("exit")) {
                break;
            }
            else {
                System.out.println(parts[0] + ": command not found");
            }   
        }
        sc.close();
    }
}
