/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.webconsole.internal.misc;


import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.webconsole.ConfigurationPrinter;
import org.apache.felix.webconsole.WebConsoleConstants;
import org.apache.felix.webconsole.internal.BaseWebConsolePlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.osgi.service.prefs.PreferencesService;
import org.osgi.util.tracker.ServiceTracker;


public class ConfigurationRender extends BaseWebConsolePlugin
{

    public static final String LABEL = "config";

    public static final String TITLE = "Configuration Status";

    /**
     * Formatter pattern to generate a relative path for the generation
     * of the plain text or zip file representation of the status. The file
     * name consists of a base name and the current time of status generation.
     */
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat( "'" + LABEL
        + "/configuration-status-'yyyyMMdd'-'HHmmZ" );

    /**
     * Formatter pattern to render the current time of status generation.
     */
    private static final DateFormat DISPLAY_DATE_FORMAT = SimpleDateFormat.getDateTimeInstance( SimpleDateFormat.LONG,
        SimpleDateFormat.LONG, Locale.US );

    private ServiceTracker cfgPrinterTracker;

    private int cfgPrinterTrackerCount;

    private SortedMap configurationPrinters = new TreeMap();


    public String getTitle()
    {
        return TITLE;
    }


    public String getLabel()
    {
        return LABEL;
    }


    protected void doGet( HttpServletRequest request, HttpServletResponse response ) throws ServletException,
        IOException
    {
        if ( request.getPathInfo().endsWith( ".txt" ) )
        {
            response.setContentType( "text/plain; charset=utf-8" );
            ConfigurationWriter pw = new PlainTextConfigurationWriter( response.getWriter() );
            printConfigurationStatus( pw );
            pw.flush();
        }
        else if ( request.getPathInfo().endsWith( ".zip" ) )
        {
            String type = getServletContext().getMimeType( request.getPathInfo() );
            if ( type == null )
            {
                type = "application/x-zip";
            }
            response.setContentType( type );

            ZipOutputStream zip = new ZipOutputStream( response.getOutputStream() );
            zip.setLevel( 9 );
            zip.setMethod( ZipOutputStream.DEFLATED );

            ConfigurationWriter pw = new ZipConfigurationWriter( zip );
            printConfigurationStatus( pw );
            pw.flush();

            zip.finish();
        }
        else
        {
            super.doGet( request, response );
        }
    }


    protected void renderContent( HttpServletRequest request, HttpServletResponse response ) throws IOException
    {

        ConfigurationWriter pw = new HtmlConfigurationWriter( response.getWriter() );

        String appRoot = ( String ) request.getAttribute( WebConsoleConstants.ATTR_APP_ROOT );
        pw.println( "<link href='" + appRoot + "/res/ui/configurationrender.css' rel='stylesheet' type='text/css'>" );
        pw.println( "<script src='" + appRoot + "/res/ui/tw-1.1.js' language='JavaScript'></script>" );

        pw.println( "<script>$(document).ready(function(){ $('#cfgprttabs').tabworld() });</script>" );

        final Date currentTime = new Date();
        synchronized ( DISPLAY_DATE_FORMAT )
        {
            pw.println( "<p>Date: " + DISPLAY_DATE_FORMAT.format( currentTime ) + "</p>" );
        }

        synchronized ( FILE_NAME_FORMAT )
        {
            String fileName = FILE_NAME_FORMAT.format( currentTime );
            pw.println( "<p>Download as <a href='" + fileName + ".txt'>[Single File]</a> or as <a href='" + fileName
                + ".zip'>[ZIP]</a></p>" );
        }

        pw.println( "<div id='divcfgprttabs'>" );

        pw.println( "<ul id='cfgprttabs'>" );

        printConfigurationStatus( pw );

        pw.println( "</ul>" );
        pw.println( "</div>" );

        pw.flush();
    }


    private void printConfigurationStatus( ConfigurationWriter pw )
    {
        this.printSystemProperties( pw );
        this.printServices( pw );
        this.printPreferences( pw );
        this.printConfigurations( pw );
        this.printThreads( pw );

        for ( Iterator cpi = getConfigurationPrinters().iterator(); cpi.hasNext(); )
        {
            printConfigurationPrinter( pw, ( ConfigurationPrinter ) cpi.next() );
        }
    }


    private Collection getConfigurationPrinters()
    {
        if ( cfgPrinterTracker == null )
        {
            cfgPrinterTracker = new ServiceTracker( getBundleContext(), ConfigurationPrinter.SERVICE, null );
            cfgPrinterTracker.open();
            cfgPrinterTrackerCount = -1;
        }

        if ( cfgPrinterTrackerCount != cfgPrinterTracker.getTrackingCount() )
        {
            SortedMap cp = new TreeMap();
            Object[] services = cfgPrinterTracker.getServices();
            if ( services != null )
            {
                for ( int i = 0; i < services.length; i++ )
                {
                    Object srv = services[i];
                    ConfigurationPrinter cfgPrinter = ( ConfigurationPrinter ) srv;
                    cp.put( cfgPrinter.getTitle(), cfgPrinter );
                }
            }
            configurationPrinters = cp;
            cfgPrinterTrackerCount = cfgPrinterTracker.getTrackingCount();
        }

        return configurationPrinters.values();
    }


    private void printSystemProperties( ConfigurationWriter pw )
    {
        pw.title( "System properties" );

        Properties props = System.getProperties();
        SortedSet keys = new TreeSet( props.keySet() );
        for ( Iterator ki = keys.iterator(); ki.hasNext(); )
        {
            Object key = ki.next();
            this.infoLine( pw, null, ( String ) key, props.get( key ) );
        }

        pw.end();
    }


    // This is Sling stuff, we comment it out for now
    //    private void printRawFrameworkProperties(PrintWriter pw) {
    //        pw.println("*** Raw Framework properties:");
    //
    //        File file = new File(getBundleContext().getProperty("sling.home"),
    //            "sling.properties");
    //        if (file.exists()) {
    //            Properties props = new Properties();
    //            InputStream ins = null;
    //            try {
    //                ins = new FileInputStream(file);
    //                props.load(ins);
    //            } catch (IOException ioe) {
    //                // handle or ignore
    //            } finally {
    //                IOUtils.closeQuietly(ins);
    //            }
    //
    //            SortedSet keys = new TreeSet(props.keySet());
    //            for (Iterator ki = keys.iterator(); ki.hasNext();) {
    //                Object key = ki.next();
    //                this.infoLine(pw, null, (String) key, props.get(key));
    //            }
    //
    //        } else {
    //            pw.println("  No Framework properties in " + file);
    //        }
    //
    //        pw.println();
    //    }


    private void printServices( ConfigurationWriter pw )
    {
        pw.title(  "Services" );

        // get the list of services sorted by service ID (ascending)
        SortedMap srMap = new TreeMap();
        try
        {
            ServiceReference[] srs = getBundleContext().getAllServiceReferences( null, null );
            for ( int i = 0; i < srs.length; i++ )
            {
                srMap.put( srs[i].getProperty( Constants.SERVICE_ID ), srs[i] );
            }
        }
        catch ( InvalidSyntaxException ise )
        {
            // should handle, for now just print nothing, actually this is not
            // expected
        }

        for ( Iterator si = srMap.values().iterator(); si.hasNext(); )
        {
            ServiceReference sr = ( ServiceReference ) si.next();

            this.infoLine( pw, null, String.valueOf( sr.getProperty( Constants.SERVICE_ID ) ), sr
                .getProperty( Constants.OBJECTCLASS ) );
            this.infoLine( pw, "  ", "Bundle", this.getBundleString( sr.getBundle() ) );

            Bundle[] users = sr.getUsingBundles();
            if ( users != null && users.length > 0 )
            {
                for ( int i = 0; i < users.length; i++ )
                {
                    this.infoLine( pw, "  ", "Using Bundle", this.getBundleString( users[i] ) );
                }
            }

            String[] keys = sr.getPropertyKeys();
            Arrays.sort( keys );
            for ( int i = 0; i < keys.length; i++ )
            {
                if ( !Constants.SERVICE_ID.equals( keys[i] ) && !Constants.OBJECTCLASS.equals( keys[i] ) )
                {
                    this.infoLine( pw, "  ", keys[i], sr.getProperty( keys[i] ) );
                }
            }

            pw.println();
        }

        pw.end();
    }


    private void printPreferences( ConfigurationWriter pw )
    {
        pw.title( "Preferences" );

        ServiceReference sr = getBundleContext().getServiceReference( PreferencesService.class.getName() );
        if ( sr == null )
        {
            pw.println( "  Preferences Service not registered" );
        }
        else
        {
            PreferencesService ps = ( PreferencesService ) getBundleContext().getService( sr );
            try
            {
                this.printPreferences( pw, ps.getSystemPreferences() );

                String[] users = ps.getUsers();
                for ( int i = 0; users != null && i < users.length; i++ )
                {
                    pw.println( "*** User Preferences " + users[i] + ":" );
                    this.printPreferences( pw, ps.getUserPreferences( users[i] ) );
                }
            }
            catch ( BackingStoreException bse )
            {
                // todo or not :-)
            }
            finally
            {
                getBundleContext().ungetService( sr );
            }
        }

        pw.end();
    }


    private void printPreferences( PrintWriter pw, Preferences prefs ) throws BackingStoreException
    {

        String[] children = prefs.childrenNames();
        for ( int i = 0; i < children.length; i++ )
        {
            this.printPreferences( pw, prefs.node( children[i] ) );
        }

        String[] keys = prefs.keys();
        for ( int i = 0; i < keys.length; i++ )
        {
            this.infoLine( pw, null, prefs.absolutePath() + "/" + keys[i], prefs.get( keys[i], null ) );
        }

        pw.println();
    }


    private void printConfigurations( ConfigurationWriter pw )
    {
        pw.title(  "Configurations" );

        ServiceReference sr = getBundleContext().getServiceReference( ConfigurationAdmin.class.getName() );
        if ( sr == null )
        {
            pw.println( "  Configuration Admin Service not registered" );
        }
        else
        {

            ConfigurationAdmin ca = ( ConfigurationAdmin ) getBundleContext().getService( sr );
            try
            {
                Configuration[] configs = ca.listConfigurations( null );
                if ( configs != null && configs.length > 0 )
                {
                    SortedMap sm = new TreeMap();
                    for ( int i = 0; i < configs.length; i++ )
                    {
                        sm.put( configs[i].getPid(), configs[i] );
                    }

                    for ( Iterator mi = sm.values().iterator(); mi.hasNext(); )
                    {
                        this.printConfiguration( pw, ( Configuration ) mi.next() );
                    }
                }
                else
                {
                    pw.println( "  No Configurations available" );
                }
            }
            catch ( Exception e )
            {
                // todo or not :-)
            }
            finally
            {
                getBundleContext().ungetService( sr );
            }
        }

        pw.end();
    }


    private void printConfigurationPrinter( ConfigurationWriter pw, ConfigurationPrinter cp )
    {
        pw.title(  cp.getTitle() );
        cp.printConfiguration( pw );
        pw.end();
    }


    private void printConfiguration( PrintWriter pw, Configuration config )
    {
        this.infoLine( pw, "", "PID", config.getPid() );

        if ( config.getFactoryPid() != null )
        {
            this.infoLine( pw, "  ", "Factory PID", config.getFactoryPid() );
        }

        String loc = ( config.getBundleLocation() != null ) ? config.getBundleLocation() : "Unbound";
        this.infoLine( pw, "  ", "BundleLocation", loc );

        Dictionary props = config.getProperties();
        if ( props != null )
        {
            SortedSet keys = new TreeSet();
            for ( Enumeration ke = props.keys(); ke.hasMoreElements(); )
            {
                keys.add( ke.nextElement() );
            }

            for ( Iterator ki = keys.iterator(); ki.hasNext(); )
            {
                String key = ( String ) ki.next();
                this.infoLine( pw, "  ", key, props.get( key ) );
            }
        }

        pw.println();
    }


    private void infoLine( PrintWriter pw, String indent, String label, Object value )
    {
        if ( indent != null )
        {
            pw.print( indent );
        }

        if ( label != null )
        {
            pw.print( label );
            pw.print( '=' );
        }

        this.printObject( pw, value );

        pw.println();
    }


    private void printObject( PrintWriter pw, Object value )
    {
        if ( value == null )
        {
            pw.print( "null" );
        }
        else if ( value.getClass().isArray() )
        {
            this.printArray( pw, ( Object[] ) value );
        }
        else
        {
            pw.print( value );
        }
    }


    private void printArray( PrintWriter pw, Object[] values )
    {
        pw.print( '[' );
        if ( values != null && values.length > 0 )
        {
            for ( int i = 0; i < values.length; i++ )
            {
                if ( i > 0 )
                {
                    pw.print( ", " );
                }
                this.printObject( pw, values[i] );
            }
        }
        pw.print( ']' );
    }


    private String getBundleString( Bundle bundle )
    {
        StringBuffer buf = new StringBuffer();

        if ( bundle.getSymbolicName() != null )
        {
            buf.append( bundle.getSymbolicName() );
        }
        else if ( bundle.getLocation() != null )
        {
            buf.append( bundle.getLocation() );
        }
        else
        {
            buf.append( bundle.getBundleId() );
        }

        Dictionary headers = bundle.getHeaders();
        if ( headers.get( Constants.BUNDLE_VERSION ) != null )
        {
            buf.append( " (" ).append( headers.get( Constants.BUNDLE_VERSION ) ).append( ')' );
        }

        if ( headers.get( Constants.BUNDLE_NAME ) != null )
        {
            buf.append( " \"" ).append( headers.get( Constants.BUNDLE_NAME ) ).append( '"' );
        }

        return buf.toString();
    }


    private void printThreads( ConfigurationWriter pw )
    {
        // first get the root thread group
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while ( rootGroup.getParent() != null )
        {
            rootGroup = rootGroup.getParent();
        }

        pw.title(  "Threads" );

        printThreadGroup( pw, rootGroup );

        int numGroups = rootGroup.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[2 * numGroups];
        rootGroup.enumerate( groups );
        for ( int i = 0; i < groups.length; i++ )
        {
            printThreadGroup( pw, groups[i] );
        }

        pw.end();
    }


    private void printThreadGroup( PrintWriter pw, ThreadGroup group )
    {
        if ( group != null )
        {
            StringBuffer info = new StringBuffer();
            info.append("ThreadGroup ").append(group.getName());
            info.append( " [" );
            info.append( "maxprio=" ).append( group.getMaxPriority() );

            info.append( ", parent=" );
            if ( group.getParent() != null )
            {
                info.append( group.getParent().getName() );
            }
            else
            {
                info.append( '-' );
            }

            info.append( ", isDaemon=" ).append( group.isDaemon() );
            info.append( ", isDestroyed=" ).append( group.isDestroyed() );
            info.append( ']' );

            infoLine( pw, null, null, info.toString() );

            int numThreads = group.activeCount();
            Thread[] threads = new Thread[numThreads * 2];
            group.enumerate( threads, false );
            for ( int i = 0; i < threads.length; i++ )
            {
                printThread( pw, threads[i] );
            }

            pw.println();
        }
    }


    private void printThread( PrintWriter pw, Thread thread )
    {
        if ( thread != null )
        {
            StringBuffer info = new StringBuffer();
            info.append("Thread ").append( thread.getName() );
            info.append( " [" );
            info.append( "priority=" ).append( thread.getPriority() );
            info.append( ", alive=" ).append( thread.isAlive() );
            info.append( ", daemon=" ).append( thread.isDaemon() );
            info.append( ", interrupted=" ).append( thread.isInterrupted() );
            info.append( ", loader=" ).append( thread.getContextClassLoader() );
            info.append( ']' );

            infoLine( pw, "  ", null, info.toString() );
        }
    }

    private abstract static class ConfigurationWriter extends PrintWriter
    {

        ConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        abstract void title( String title );


        abstract void end();

    }

    private static class HtmlConfigurationWriter extends ConfigurationWriter
    {

        HtmlConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        public void title( String title )
        {
            println( "<li>" );
            println( title );
            println( "<q><pre>" );
        }


        public void end()
        {
            println( "</pre>" );
            println( "</q>" );
            println( "</li>" );
        }
    }

    private static class PlainTextConfigurationWriter extends ConfigurationWriter
    {

        PlainTextConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        public void title( String title )
        {
            print( "*** " );
            print( title );
            println( ":" );
        }


        public void end()
        {
            println();
        }
    }

    private static class ZipConfigurationWriter extends ConfigurationWriter
    {
        private final ZipOutputStream zip;

        private int counter;


        ZipConfigurationWriter( ZipOutputStream zip )
        {
            super( new OutputStreamWriter( zip ) );
            this.zip = zip;
        }


        public void title( String title )
        {
            String name = MessageFormat.format( "{0,number,000}-{1}.txt", new Object[]
                { new Integer( counter ), title } );

            counter++;

            ZipEntry entry = new ZipEntry( name );
            try
            {
                zip.putNextEntry( entry );
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }


        public void end()
        {
            flush();

            try
            {
                zip.closeEntry();
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }
    }
}
