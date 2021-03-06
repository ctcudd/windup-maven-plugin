/*
* Copyright 2014 Red Hat, Inc. and/or its affiliates.
*
* Licensed under the Eclipse Public License version 1.0, available at
* http://www.eclipse.org/legal/epl-v10.html
*/

package org.jboss.windup.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.commons.lang3.StringUtils;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.forge.furnace.Furnace;
import org.jboss.forge.furnace.addons.AddonId;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.impl.addons.AddonRepositoryImpl;
import org.jboss.forge.furnace.manager.impl.AddonManagerImpl;
import org.jboss.forge.furnace.manager.maven.MavenContainer;
import org.jboss.forge.furnace.manager.maven.addon.MavenAddonDependencyResolver;
import org.jboss.forge.furnace.manager.request.AddonActionRequest;
import org.jboss.forge.furnace.manager.spi.AddonDependencyResolver;
import org.jboss.forge.furnace.repositories.AddonRepositoryMode;
import org.jboss.forge.furnace.se.FurnaceFactory;
import org.jboss.forge.furnace.versions.SingleVersion;
import org.jboss.forge.furnace.versions.Version;
import org.jboss.forge.furnace.versions.Versions;
import org.jboss.windup.config.KeepWorkDirsOption;
import org.jboss.windup.exec.WindupProcessor;
import org.jboss.windup.exec.configuration.WindupConfiguration;
import org.jboss.windup.exec.configuration.options.ExcludeTagsOption;
import org.jboss.windup.exec.configuration.options.ExplodedAppInputOption;
import org.jboss.windup.exec.configuration.options.IncludeTagsOption;
import org.jboss.windup.exec.configuration.options.OverwriteOption;
import org.jboss.windup.exec.configuration.options.SourceOption;
import org.jboss.windup.exec.configuration.options.TargetOption;
import org.jboss.windup.graph.GraphContext;
import org.jboss.windup.graph.GraphContextFactory;
import org.jboss.windup.rules.apps.java.config.ExcludePackagesOption;
import org.jboss.windup.rules.apps.java.config.ScanPackagesOption;
import org.jboss.windup.rules.apps.java.config.SourceModeOption;
import org.jboss.windup.rules.apps.java.reporting.rules.EnableCompatibleFilesReportOption;
import org.jboss.windup.rules.apps.tattletale.EnableTattletaleReportOption;
import org.jboss.windup.util.PathUtil;
import org.jboss.windup.util.ZipUtil;
import org.jboss.windup.util.exception.WindupException;

/**
 * @author <a href="mailto:samueltauil@gmail.com">Samuel Tauil</a>
 * @author <a href="mailto:zizka@seznam.cz">Ondrej Zizka</a>
 */
@Mojo(name = "windup", requiresDependencyResolution = ResolutionScope.COMPILE, aggregator = true)
@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class WindupMojo extends AbstractMojo
{
    private static final String VERSION_DEFINITIONS_FILE = "META-INF/versions.properties";


    @Parameter(defaultValue = "${project.build.directory}")
    private String buildDirectory;

    /**
     * Location of the generated report files.
     */
    @Parameter( alias = "output", property = "output", defaultValue = "${project.build.directory}/windup-report", required = true)
    private String outputDirectory;

    /**
     * Location of the input file application.
     */
    @Parameter( alias="input", property = "input", required = true, defaultValue = "${project.build.sourceDirectory}" )
    private String inputDirectory;

    /**
     * Packages to be inspected by Windup.
     */
    @Parameter( alias="packages", property = "packages", required = true)
    private List<String> packages;

    @Parameter( alias="excludePackages", property = "excludePackages", required = false)
    private List<String> excludePackages;

    @Parameter( alias = "offline", property = "offline", required = false)
    private Boolean offlineMode;

    @Parameter( alias="sourceMode", property = "sourceMode", required = false, defaultValue = "true" )
    private Boolean sourceMode;

    @Parameter( alias="overwrite", property = "overwrite", required = false )
    private Boolean overwrite;

    @Parameter( alias="userIgnorePath", property = "userIgnorePath", required = false)
    private String userIgnorePath;

    @Parameter( alias="userRulesDirectory", property = "userRulesDirectory", required = false)
    private String userRulesDirectory;

    @Parameter( alias="includeTags", property = "includeTags", required = false )
    private List<String> includeTags;

    @Parameter( alias="excludeTags", property = "excludeTags", required = false )
    private List<String> excludeTags;

    @Parameter( alias="sourceTechnologies", property = "sourceTechnologies", required = false )
    private List<String> sources;

    @Parameter( alias="targetTechnologies", property = "targetTechnologies", required = false)
    private List<String> targets;


    @Parameter( alias="windupVersion", property = "windupVersion", required = false )
    private String windupVersion;

    @Parameter( alias="keepWorkDirs", property = "keepWorkDirs", required = false)
    private Boolean keepWorkDirs;

    @Parameter( alias="explodedApps", property = "explodedApps", required = false)
    private Boolean explodedApps;

    @Parameter( alias="exportCSV", property = "exportCSV", required = false)
    private Boolean exportCSV;

    @Parameter( alias="enableTattletale", property = "enableTattletale", required = false)
    private Boolean enableTattletale;

    @Parameter( alias="enableCompatibleFilesReport", property = "enableCompatibleFilesReport", required = false)
    private Boolean enableCompatibleFilesReport;


    private static final String WINDUP_RULES_GROUP_ID = "org.jboss.windup.rules";
    private static final String WINDUP_RULES_ARTIFACT_ID = "windup-rulesets";


    public void execute() throws MojoExecutionException
    {
        System.setProperty(PathUtil.WINDUP_HOME, Paths.get(buildDirectory, "winduphome").toString());

        WindupConfiguration windupConfiguration = new WindupConfiguration();

        windupConfiguration.addInputPath(Paths.get(inputDirectory));
        windupConfiguration.setOutputDirectory(Paths.get(outputDirectory));

        packages        = normalizePackages(packages);
        excludePackages = normalizePackages(excludePackages);
        windupConfiguration.setOptionValue(ScanPackagesOption.NAME, packages);
        windupConfiguration.setOptionValue(ExcludePackagesOption.NAME, excludePackages);


        windupConfiguration.setOffline(offlineMode == Boolean.TRUE);
        windupConfiguration.setOptionValue(SourceModeOption.NAME, sourceMode == Boolean.TRUE);
        windupConfiguration.setOptionValue(ExplodedAppInputOption.NAME, explodedApps == Boolean.TRUE);
        windupConfiguration.setOptionValue(OverwriteOption.NAME, overwrite == Boolean.TRUE);

        windupConfiguration.setOptionValue(IncludeTagsOption.NAME, includeTags);
        windupConfiguration.setOptionValue(ExcludeTagsOption.NAME, excludeTags);
        windupConfiguration.setOptionValue(SourceOption.NAME, sources);
        windupConfiguration.setOptionValue(TargetOption.NAME, targets);

        windupConfiguration.setOptionValue(KeepWorkDirsOption.NAME, keepWorkDirs == Boolean.TRUE);
        windupConfiguration.setOptionValue(EnableCompatibleFilesReportOption.NAME, enableCompatibleFilesReport);
        windupConfiguration.setOptionValue(EnableTattletaleReportOption.NAME, enableTattletale == Boolean.TRUE);
        windupConfiguration.setExportingCSV(exportCSV == Boolean.TRUE);

        downloadAndUnzipRules();

        windupConfiguration.addDefaultUserRulesDirectory(PathUtil.getWindupRulesDir());

        if (userRulesDirectory != null && !Files.isDirectory(Paths.get(userRulesDirectory)))
        {
            try
            {
                Files.createDirectories(Paths.get(userRulesDirectory));
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        if (userRulesDirectory != null)
            windupConfiguration.addDefaultUserRulesDirectory(Paths.get(userRulesDirectory));

        if (userIgnorePath == null)
        	userIgnorePath = PathUtil.getWindupIgnoreDir().toString();
        windupConfiguration.addDefaultUserIgnorePath(Paths.get(userIgnorePath));


        Properties versions;
        try
        {
            versions = loadVersions(VERSION_DEFINITIONS_FILE);
        }
        catch (IOException ex)
        {
            final String msg = "Can't load the version definitions from classpath: " + VERSION_DEFINITIONS_FILE;
            throw new MojoExecutionException(msg, ex);
        }

        final String furnaceVersion = versions.getProperty("version.furnace");
        if(furnaceVersion == null)
            throw new MojoExecutionException("Internal error: Version of Furnace was not defined.");

        final String forgeVersion = versions.getProperty("version.forge");
        if(forgeVersion == null)
            throw new MojoExecutionException("Internal error: Version of Forge was not defined.");

        final String windupVersion_ = versions.getProperty("version.windup");
        if(null != windupVersion_)
            this.windupVersion = windupVersion_;
        if(null == this.windupVersion)
            throw new MojoExecutionException("Internal error: Version of Windup which should be used was not defined.");

        Furnace furnace = createAndStartFurnace();
        install("org.jboss.forge.furnace.container:simple," + furnaceVersion, furnace); // :simple instead of :cdi
        install("org.jboss.forge.addon:core," + forgeVersion, furnace);
        install("org.jboss.windup:windup-tooling," + this.windupVersion, furnace);
        install("org.jboss.windup.rules.apps:windup-rules-java-project," + this.windupVersion, furnace);

        if(this.enableTattletale == Boolean.TRUE)
            install("org.jboss.windup.rules.apps:windup-rules-tattletale," + this.windupVersion, furnace);


        AddonRegistry addonRegistry = furnace.getAddonRegistry();
        WindupProcessor windupProcessor = addonRegistry.getServices(WindupProcessor.class).get();

        GraphContextFactory graphContextFactory = addonRegistry.getServices(GraphContextFactory.class).get();

        Path graphPath = windupConfiguration.getOutputDirectory().resolve("graph");
        try (GraphContext graphContext = graphContextFactory.create(graphPath))
        {
            windupConfiguration.setGraphContext(graphContext);
            windupProcessor.execute(windupConfiguration);
            getLog().info(
                "\n\n=========================================================================================================================="
              + "\n\n    Windup report created: " + windupConfiguration.getOutputDirectory().toAbsolutePath() + "/index.html"
              + "\n\n==========================================================================================================================\n");
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void downloadAndUnzipRules()
    {
        MavenContainer mavenContainer = new MavenContainer();
        RepositorySystem system = mavenContainer.getRepositorySystem();
        Settings settings = mavenContainer.getSettings();
        DefaultRepositorySystemSession session = mavenContainer.setupRepoSession(system, settings);
        ArtifactRequest artifactRequest = new ArtifactRequest();
        // <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
        artifactRequest.setArtifact(new DefaultArtifact(WINDUP_RULES_GROUP_ID + ":" + WINDUP_RULES_ARTIFACT_ID + ":" + windupVersion));
        try
        {
            ArtifactResult artifactResult = mavenContainer.getRepositorySystem().resolveArtifact(session, artifactRequest);
            Path outputDirectory = PathUtil.getWindupRulesDir();
            if (!Files.isDirectory(outputDirectory))
                Files.createDirectories(outputDirectory);
            ZipUtil.unzipToFolder(artifactResult.getArtifact().getFile(), outputDirectory.toFile());
        }
        catch (ArtifactResolutionException | IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Furnace createAndStartFurnace()
    {
        // Create a Furnace instance. NOTE: This must be called only once
        Furnace furnace = FurnaceFactory.getInstance();
        // Add repository containing addons specified in pom.xml
        furnace.addRepository(AddonRepositoryMode.MUTABLE, new File("target/addons"));

        // Start Furnace in another thread
        System.setProperty("INTERACTIVE", "false");
        Future<Furnace> future = furnace.startAsync();
        try
        {
            // Wait until Furnace is started and return
            return future.get();
        }
        catch (InterruptedException | ExecutionException ex)
        {
            throw new WindupException("Failed to start Furnace: " + ex.getMessage(), ex);
        }
    }


    /**
     * TODO: Copied from Windup. Refactor.
     */
    private void install(String addonCoordinates, Furnace furnace)
    {
        Version runtimeAPIVersion = AddonRepositoryImpl.getRuntimeAPIVersion();
        try
        {
            AddonDependencyResolver resolver = new MavenAddonDependencyResolver();
            AddonManagerImpl addonManager = new AddonManagerImpl(furnace, resolver);

            AddonId addon = null;
            // This allows windup --install maven
            if (addonCoordinates.contains(","))
                addon = AddonId.fromCoordinates(addonCoordinates);
            else
                addon = pickLatestAddonVersion(resolver, addonCoordinates, runtimeAPIVersion, addon);

            if (addon == null)
                throw new IllegalArgumentException("No compatible addon API version found for " + addonCoordinates + " for API " + runtimeAPIVersion);

            AddonActionRequest request = addonManager.install(addon);
            getLog().info("Requesting to install: " + request.toString());
            request.perform();
            getLog().info("Installation completed successfully.\n");
        }
        catch (Exception e)
        {
            getLog().error(e);
            getLog().error("> Forge version [" + runtimeAPIVersion + "]");
        }
    }


    private AddonId pickLatestAddonVersion(AddonDependencyResolver resolver, String addonCoordinates, Version runtimeAPIVersion, AddonId addon) throws IllegalArgumentException
    {
        AddonId[] versions = resolver.resolveVersions(addonCoordinates).get();
        if (versions.length == 0)
            throw new IllegalArgumentException("No Artifact version found for " + addonCoordinates);
        for (int i = versions.length - 1; i >= 0; i--)
        {
            String apiVersion = resolver.resolveAPIVersion(versions[i]).get();
            if (apiVersion != null && Versions.isApiCompatible(runtimeAPIVersion, SingleVersion.valueOf(apiVersion)))
                return versions[i];
        }
        return null;
    }

    private Properties loadVersions(String path) throws IOException {
        final InputStream propsFile = WindupMojo.class.getClassLoader().getResourceAsStream(path);
        Properties props = new Properties();
        props.load(propsFile);
        return props;
    }


    /**
     * Removes the .* suffix, which is expectable the users will use.
     */
    private List<String> normalizePackages(List<String> packages)
    {
        if (packages == null)
            return null;

        List<String> result = new ArrayList<>(packages.size());
        for (String pkg : packages)
        {
            if(pkg.endsWith(".*")){
                getLog().warn("Warning: removing the .* suffix from the package prefix: " + pkg);
            }
            result.add(StringUtils.removeEndIgnoreCase(pkg, ".*"));
        }

        return packages;
    }
}
