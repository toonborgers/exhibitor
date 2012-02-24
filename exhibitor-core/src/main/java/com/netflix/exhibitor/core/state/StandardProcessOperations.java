package com.netflix.exhibitor.core.state;

import com.google.common.collect.Iterables;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.ActivityLog;
import com.netflix.exhibitor.core.cluster.ServerList;
import com.netflix.exhibitor.core.cluster.ServerSpec;
import com.netflix.exhibitor.core.config.InstanceConfig;
import com.netflix.exhibitor.core.config.IntConfigs;
import com.netflix.exhibitor.core.config.StringConfigs;
import java.io.*;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StandardProcessOperations implements ProcessOperations
{
    private final Exhibitor exhibitor;

    private static final String     MODIFIED_CONFIG_NAME = "exhibitor.cfg";

    public StandardProcessOperations(Exhibitor exhibitor) throws IOException
    {
        this.exhibitor = exhibitor;
    }

    @Override
    public void cleanupInstance() throws Exception
    {
        Details             details = new Details(exhibitor);
        if ( !details.isValid() )
        {
            return;
        }

        // see http://zookeeper.apache.org/doc/r3.3.3/zookeeperAdmin.html#Ongoing+Data+Directory+Cleanup
        ProcessBuilder      builder = new ProcessBuilder
        (
            "java",
            "-cp",
            String.format("%s:%s:%s", details.zooKeeperJarPath.getPath(), details.log4jJarPath.getPath(), details.configDirectory.getPath()),
            "org.apache.zookeeper.server.PurgeTxnLog",
            details.dataDirectory.getPath(),
            details.dataDirectory.getPath(),
            "-n",
            Integer.toString(exhibitor.getConfigManager().getConfig().getInt(IntConfigs.CLEANUP_MAX_FILES))
        );

        ExecutorService errorService = Executors.newSingleThreadExecutor();
        StringWriter        errors = new StringWriter();
        final PrintWriter   errorOut = new PrintWriter(errors);
        try
        {
            Process             process = builder.start();
            final InputStream   errorStream = process.getErrorStream();
            errorService.submit
            (
                new Callable<Object>()
                {
                    public Object call() throws Exception
                    {
                        BufferedReader      in = new BufferedReader(new InputStreamReader(errorStream));
                        for(;;)
                        {
                            String  line = in.readLine();
                            if ( line == null )
                            {
                                break;
                            }
                            errorOut.println(line);
                        }
                        return null;
                    }
                }
            );
            process.waitFor();

            errorOut.close();
            String      errorStr = errors.toString();
            if ( errorStr.length() > 0 )
            {
                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Cleanup task reported errors: " + errorStr);
            }
            else
            {
                exhibitor.getLog().add(ActivityLog.Type.INFO, "Cleanup task ran successfully");
            }
        }
        finally
        {
            errorService.shutdownNow();
        }
    }

    @Override
    public void killInstance() throws Exception
    {
        exhibitor.getLog().add(ActivityLog.Type.INFO, "Attempting to start/restart ZooKeeper");

        ProcessBuilder          builder = new ProcessBuilder("jps");
        Process                 jpsProcess = builder.start();
        String                  pid = null;
        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(jpsProcess.getInputStream()));
            for(;;)
            {
                String  line = in.readLine();
                if ( line == null )
                {
                    break;
                }
                String[]  components = line.split("[ \t]");
                if ( (components.length == 2) && components[1].equals("QuorumPeerMain") )
                {
                    pid = components[0];
                    break;
                }
            }
        }
        finally
        {
            jpsProcess.destroy();
        }

        if ( pid == null )
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "jps didn't find instance - assuming ZK is not running");
        }
        else
        {
            builder = new ProcessBuilder("kill", "-9", pid);
            try
            {
                int     result = builder.start().waitFor();
                exhibitor.getLog().add(ActivityLog.Type.INFO, "Kill attempted result: " + result);
            }
            catch ( InterruptedException e )
            {
                // don't reset thread interrupted status

                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Process interrupted while running: kill -9 " + pid);
                throw e;
            }
        }
    }

    @Override
    public void startInstance() throws Exception
    {
        Details         details = new Details(exhibitor);

        File            configFile = prepConfigFile(details);
        File            binDirectory = new File(details.zooKeeperDirectory, "bin");
        File            startScript = new File(binDirectory, "zkServer.sh");
        ProcessBuilder  builder = new ProcessBuilder(startScript.getPath(), "start").directory(binDirectory.getParentFile());
        builder.environment().put("ZOOCFG", configFile.getName());
        builder.start();

        exhibitor.getLog().add(ActivityLog.Type.INFO, "Process started via: " + startScript.getPath());
    }

    private File prepConfigFile(Details details) throws IOException
    {
        InstanceConfig          config = exhibitor.getConfigManager().getConfig();

        ServerList              serverList = new ServerList(config.getString(StringConfigs.SERVERS_SPEC));

        File                    idFile = new File(details.dataDirectory, "myid");
        ServerSpec us = Iterables.find(serverList.getSpecs(), ServerList.isUs(exhibitor.getThisJVMHostname()), null);
        if ( us != null )
        {
            Files.createParentDirs(idFile);
            String                  id = String.format("%d\n", us.getServerId());
            Files.write(id.getBytes(), idFile);
        }
        else
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "Starting in standalone mode");
            if ( idFile.exists() && !idFile.delete() )
            {
                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Could not delete ID file: " + idFile);
            }
        }

        Properties      localProperties = new Properties();
        localProperties.putAll(details.properties);

        localProperties.setProperty("clientPort", Integer.toString(config.getInt(IntConfigs.CLIENT_PORT)));

        String          portSpec = String.format(":%d:%d", config.getInt(IntConfigs.CONNECT_PORT), config.getInt(IntConfigs.ELECTION_PORT));
        for ( ServerSpec spec : serverList.getSpecs() )
        {
            localProperties.setProperty("server." + spec.getServerId(), spec.getHostname() + portSpec);
        }

        File            configFile = new File(details.configDirectory, MODIFIED_CONFIG_NAME);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(configFile));
        try
        {
            localProperties.store(out, "Auto-generated by Exhibitor - " + new Date());
        }
        finally
        {
            Closeables.closeQuietly(out);
        }

        return configFile;
    }
}
