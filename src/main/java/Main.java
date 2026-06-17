import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input  = sc.nextLine().trim();
            String[] parts = input.split(" ");

            if (parts[0].equals("echo")) {
                for (int i=1; i<parts.length; i++) {
                    System.out.print(parts[i]);
                    System.out.print((i < parts.length-1) ? " ":"");
                }
                System.out.println();
            }
            else if (parts[0].equals("type")) {
                String curr = parts[1];
                if (curr.equals("echo") || curr.equals("exit") || curr.equals("type")) {
                    System.out.println(curr+" is a shell builtin");
                }
                else {
                    System.out.println(curr+": not found");
                }
            }
            else if (parts[0].equals("exit")) {
                sc.close();
                break;
            }
            else System.out.println(input+": command not found");    
            // sc.close();   
        }
    }
}
