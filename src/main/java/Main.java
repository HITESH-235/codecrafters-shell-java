import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.print("$ ");
        String input  = sc.nextLine();
        System.out.println(input+": command not found");    
        sc.close();    
    }
}
