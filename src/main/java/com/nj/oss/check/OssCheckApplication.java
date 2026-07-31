package com.nj.oss.check;

import com.nj.oss.check.cli.ExecutionErrorHandler;
import com.nj.oss.check.cli.ExitCode;
import com.nj.oss.check.cli.OssCheckCommand;
import com.nj.oss.check.cli.SpringFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class OssCheckApplication {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        // Spring is started with no arguments on purpose: the command line
        // belongs to picocli. Handing argv to Spring as well would turn options
        // like --user into application properties.
        try (ConfigurableApplicationContext context =
                     new SpringApplication(OssCheckApplication.class).run()) {
            return OssCheckCommand.commandLine(new SpringFactory(context)).execute(args);
        } catch (Exception e) {
            // Failing to start is a failure to run, not a diagnosis. Letting
            // this escape would end the JVM with code 1 — "findings reported".
            System.err.println("oss-check could not start: " + ExecutionErrorHandler.describe(e));
            return ExitCode.ERROR;
        }
    }

}
