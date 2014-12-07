import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.apache.tika.metadata.Metadata;

/**
 * 
 */

/**
 * @author alex
 *
 */
public class FIMcli
{
	private String sourcepath;	// path for collecting files
	private String homepath;	// path of the running program (working directory)
	private String dbname;		// database name
	private String solrconn;	// url to the solr connection
	private int commiton;		// limit after commit docs
	private int maxpathdeph;	// max expected charlenght for the path of collected files
	
	private Connection dbcon = null; // connection to local h2 database
	
	/**
	 * constructor, setting default values for classfields
	 */
	public FIMcli()
	{
		this.sourcepath = System.getProperty("user.dir");
		this.homepath = System.getProperty("user.dir");
		this.dbname = "fimdb.h2db";
		this.solrconn = "http://localhost:8080/solr/";
		this.commiton = 100;
		this.maxpathdeph = 1024;
	}

	/**
	 * @param args
	 */
	public static void main(String[] args)
	{
		FIMcli fimcli = new FIMcli();
		// check the params and env
		System.out.println(fimcli.checkparams(args));
		
		if (fimcli.verifydirs() && 
			fimcli.veryfysolrconn() && 
			fimcli.initdb())
		{
			if (fimcli.firstrun())
				fimcli.fullimport();
			else
				fimcli.update();
		}
		fimcli.closedb();

		System.out.println("done");
	}


	/**
	 * - running an update to the database and solr
	 * - cleaning database
	 */
	private void update()
	{
		System.out.println("starting an update...");
		
		String updateruntabname = this.getruntabname();
		this.doarun(updateruntabname);
		
		this.update2solr();
		
		this.cleanruns();
	}

	/**
	 * deleting old runs from the database and keeping only the last two
	 */
	private void cleanruns()
	{
		try
		{
			// get last two runs
			ResultSet rsetruns = this.dbselect("SELECT tabname FROM main WHERE finished=TRUE ORDER BY mainid DESC");
			int i=0;
			while(rsetruns.next())
			{
				i++;
				if (i>2)
				{
					this.dbinupdel("DELETE FROM main WHERE tabname='"+rsetruns.getString(1)+"'");
					this.dbinupdel("DROP TABLE "+rsetruns.getString(1));
				}
			}
			rsetruns.close();
		
		} catch (SQLException e)
		{
			System.err.println(e+" database error while cleaning runs o_o");
			e.printStackTrace();
		}
	}

	/**
	 * - compare the last two runs and collect changed files
	 * - submits deletes and adds to solr
	 */
	private void update2solr()
	{
		try
		{
			// get last two runs
			ResultSet rsetruns = this.dbselect("SELECT tabname FROM main WHERE finished=TRUE ORDER BY mainid DESC LIMIT 2");
			rsetruns.next();
			String fsttabname = rsetruns.getString(1);
			rsetruns.next();
			String sectabname = rsetruns.getString(1);
			rsetruns.close();

			// get deletes
			ResultSet rsetdels = this.dbselect("SELECT path FROM "+sectabname+" MINUS SELECT path FROM "+fsttabname);			
			this.deletdocs2solr(rsetdels);
			rsetdels.close();
			
			// get adds
			ResultSet rsetadds = this.dbselect("SELECT path FROM "+fsttabname+" MINUS SELECT path FROM "+sectabname);
			this.adddocs2solr(rsetadds);
			rsetadds.close();

		} catch (SQLException e)
		{
			System.err.println(e+" database error while update 2 solr -_-");
			e.printStackTrace();
		}
		
	}

	/**
	 * @param rsetadds
	 * 
	 * submit all files from the resultset as adds to solr
	 */
	private void adddocs2solr(ResultSet rsetadds)
	{
		TikaMetadata tikamdata = new TikaMetadata();
		try
		{
			HttpSolrServer solrsrv = new HttpSolrServer(this.solrconn);
			int i=0;
			while (rsetadds.next())
			{
				System.out.println("+>> '"+rsetadds.getString(1)+"'");
				SolrInputDocument solrdoc = this.createsolrdocfrommdata(rsetadds.getString(1), tikamdata);
				solrsrv.add(solrdoc);
				i++;
				
				if(i%commiton==0)
					solrsrv.commit();
			}
			solrsrv.commit();
			
		} catch (SolrServerException | IOException | SQLException e)
		{
			System.err.println(e+" failed to add docs to solr :/");
			e.printStackTrace();
		}
		
	}

	/**
	 * @param path
	 * @param tikamdata
	 * @return
	 * 
	 * gets a file, extracts its metadata and returns a solrdoc of it
	 */
	private SolrInputDocument createsolrdocfrommdata(String path, TikaMetadata tikamdata)
	{
		SolrInputDocument solrdoc = new SolrInputDocument();
		try
		{
			Metadata mdata = tikamdata.getmetadata(path);
			
			solrdoc.addField("id", path);
			solrdoc.addField("filetype", mdata.get("Content-Type"));
			solrdoc.addField("filesize", mdata.get("Content-Length"));
			solrdoc.addField("metadata", mdata.toString());
		} catch (NullPointerException e)
		{
			System.err.println(e+" failed to read '"+path+"' :E");
			solrdoc.addField("id", path);
			solrdoc.addField("filetype", "-/-");
			solrdoc.addField("filesize", "0");
			solrdoc.addField("metadata", "");
		}
		return solrdoc;
	}

	/**
	 * @param rsetdels
	 * 
	 * submit all files from the resultset as deletes to solr
	 */
	private void deletdocs2solr(ResultSet rsetdels)
	{
		try
		{
			HttpSolrServer solrsrv = new HttpSolrServer(this.solrconn);
			int i=0;
			while (rsetdels.next())
			{
				System.out.println("-<< '"+rsetdels.getString(1)+"'");
				solrsrv.deleteById(rsetdels.getString(1));
				i++;
				
				if(i%commiton==0)
					solrsrv.commit();
			}
			solrsrv.commit();
			
		} catch (SolrServerException | IOException | SQLException e)
		{
			System.err.println(e+" failed to delete docs to solr :F");
			e.printStackTrace();
		}
	}

	private void fullimport()
	{
		System.out.println("starting a full import...");

		this.createmaintab();
		
		String firstruntabname = this.getruntabname();
		this.doarun(firstruntabname);
		
		this.fullimport2solr(firstruntabname);
		
	}

	private void fullimport2solr(String runtabname)
	{
		TikaMetadata tikamdata = new TikaMetadata();

		try
		{
			ResultSet rsetfullrun = this.dbselect("SELECT path FROM "+runtabname);
			HttpSolrServer solrsrv = new HttpSolrServer(this.solrconn);
			try
			{
				solrsrv.deleteByQuery("*:*");// CAUTION: deletes everything!
			} catch (SolrServerException | IOException e)
			{
				System.err.println(e+" failed to delete all docs before fullimport oO");
				e.printStackTrace();
			}
			
			int i=0;
			while (rsetfullrun.next())
			{
				try
				{
					SolrInputDocument solrdoc = this.createsolrdocfrommdata(rsetfullrun.getString(1), tikamdata);
					
					solrsrv.add(solrdoc);
					
					i++;
					if(i%commiton==0)
						solrsrv.commit();
				} catch (SolrServerException | IOException e)
				{
					System.err.println(e+" failed to add '"+rsetfullrun.getString(1)+"' :o");
					e.printStackTrace();
				}
			}
			rsetfullrun.close();
			
			try
			{
				solrsrv.commit();
			} catch (SolrServerException | IOException e)
			{
				System.err.println(e+" failed to commit docs :(");
				e.printStackTrace();
			}

			System.out.println("done adding docs to solr");
			
		} catch (SQLException e)
		{
			System.err.println(e+" failed to read from database :X");
			e.printStackTrace();
		}
		
	}

	private void createmaintab()
	{
		System.out.println("creating maintable...");

		this.dbinupdel("CREATE TABLE IF NOT EXISTS main ("
				+ "mainid IDENTITY PRIMARY KEY, "
				+ "tabname VARCHAR(16), "
				+ "entries BIGINT, "
				+ "finished BOOLEAN, "
				+ "tstamp TIMESTAMP"
				+ ")");
	}

	private void doarun(String runtabname)
	{
		System.out.println("doing a run on '"+runtabname+"'");

		long runcnt =0;
		
		try
		{
			this.dbinupdel("INSERT INTO main (tabname, entries, finished, tstamp) VALUES "
					+ "('"+runtabname+"', 0, FALSE, '"+new Timestamp(System.currentTimeMillis())+"')");

			this.dbinupdel("CREATE TABLE "+runtabname+" (runid IDENTITY PRIMARY KEY, path VARCHAR("+this.maxpathdeph+"))");

			PreparedStatement prepaddstmt = this.dbcon.prepareStatement("INSERT INTO "+runtabname+" (path) VALUES (?)");
			
			Files.walkFileTree(FileSystems.getDefault().getPath(this.sourcepath), new Fileadder(prepaddstmt));
			
			this.dbcon.setAutoCommit(false);
			prepaddstmt.executeBatch();
			this.dbcon.setAutoCommit(true);
			
			runcnt = this.getruncount(runtabname);

			this.dbinupdel("UPDATE main SET "
					+ "entries="+runcnt+", "
					+ "finished=TRUE, "
					+ "tstamp='"+new Timestamp(System.currentTimeMillis())+"' "
					+ "WHERE tabname='"+runtabname+"'");
			

		} catch (SQLException | IOException e)
		{
			System.err.println(e+" failed to do a run with '"+runtabname+"' :<");
			e.printStackTrace();
		}

		System.out.println("\nfinished run on '"+runtabname+"' with "+runcnt+" entries");
	}

	private long getruncount(String runtabname)
	{
		long runcnt = 0;
		try
		{
			ResultSet rsetrncnt = this.dbselect("SELECT COUNT(*) FROM "+runtabname);
			rsetrncnt.next();
			runcnt=rsetrncnt.getLong(1);
			rsetrncnt.close();
		} catch (SQLException e)
		{
			System.err.println(e+" failed to get runcount of '"+runtabname+"' :c");
			e.printStackTrace();
		}
		return runcnt;
	}

	private String getruntabname()
	{
		Calendar cal = new GregorianCalendar();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
		dateFormat.setCalendar(cal); 
		return "_"+dateFormat.format(cal.getTime())+"_";
	}

	private boolean firstrun()
	{
		System.out.println("looking for first run");
		if (this.mainexists() && this.hasfirstrun())
		{
			return false;
		}
		return true;
	}

	private boolean hasfirstrun()
	{
		boolean firstExists = false;
		
		try
		{
			ResultSet rsetmain = this.dbselect("SELECT tabname, entries, finished, tstamp FROM main ORDER BY mainid");
			while(rsetmain.next())
			{
				long firstruncnt = this.getruncount(rsetmain.getString(1));
				if (firstruncnt > 0)
				{
					if (rsetmain.getLong(2) == firstruncnt && 
						rsetmain.getBoolean(3) == true)
						firstExists = true;
				}
			}
			rsetmain.close();
		} catch (SQLException e)
		{
			System.err.println(e+" failed to check for the existing of first run :s");
			e.printStackTrace();
		}
		System.out.println("has firstrun="+firstExists);
		return firstExists;
	}

	private boolean veryfysolrconn()
	{
		HttpSolrServer solrsrv = new HttpSolrServer(this.solrconn);
		try
		{
			solrsrv.ping();
		} catch (SolrServerException | IOException e)
		{
			System.err.println(e+" solr ping failed with '"+this.solrconn+"'");
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	private boolean verifydirs()
	{
		if (new File(this.sourcepath).isDirectory() && new File(this.homepath).isDirectory())
		{
			System.out.println("sourcepath and homepath look like valid directories");
			return true;
		}
			else
		{
			System.err.println("sourcepath '"+this.sourcepath+"' and/or homepath '"+this.homepath+"' are invalid :Y");
			return false;
		}
	}

	private String checkparams(String[] params)
	{
		String retstr ="";
		for (int i=0 ; params.length>i ; i++)
		{
			if (params[0].endsWith("version"))
			{
				retstr+="program version 0.1\n";
				break;
			}
			if (params[i].contains("path"))
			{
				this.sourcepath = params[i+1];
				retstr+="setting sourcepath to '"+this.sourcepath+"'\n";
			}
			if (params[i].contains("solrurl"))
			{
				this.solrconn = params[i+1];
				retstr+="setting solr url to '"+this.solrconn+"'\n";
			}
			
		}
		
		
		
		if (retstr.isEmpty())
			retstr = getusage();
		return retstr;
	}

	/**
	 * @return usageinfo...
	 */
	private static String getusage()
	{
		return "\nprogram [options]\n\n"
		+ "--version			returns versioninfo\n"
		+ "--path [path]			sets sourcepath for fileindexing\n"
		+ "--solrurl [url]			set url to solr core\n"
		+ "";
	}
	
	private boolean mainexists()
	{
		boolean mainExists = false;
		try
		{
			ResultSet rsetmain = this.dbselect("SELECT COUNT(*) FROM information_schema.tables "
					+ "WHERE table_name = 'MAIN'");
									//     ^^^^ uppercase on purpose!
			if (rsetmain.next() && rsetmain.getInt(1) == 1)
				mainExists = true;
			rsetmain.close();
		} catch (SQLException e)
		{
			System.err.println(e+" failed to look for the main-table :\\");
			e.printStackTrace();
		}
		System.out.println("main table exists="+mainExists);
		return mainExists;
	}
	
	private ResultSet dbselect(String query)
	{
		try
		{
			return this.dbcon.createStatement().executeQuery(query);
		} catch (SQLException e)
		{
			System.err.println(e+" select '"+query+"' failed :C");
			e.printStackTrace();
		}
		return null;
	}
	
	private boolean dbinupdel(String query)
	{
		try
		{
			return this.dbcon.createStatement().execute(query);
		} catch (SQLException e)
		{
			System.err.println(e+" inupdel '"+query+"' failed :C");
			e.printStackTrace();
			return false;
		}
	}
	
	
	
	private void closedb()
	{
		System.out.println("close database...");
		if (this.dbcon != null)
		{
			try
			{
				if (!this.dbcon.isClosed())
					this.dbcon.close();
			} catch (SQLException e)
			{
				System.err.println(e+" could not close database connection :/");
				e.printStackTrace();
			}
		}
			else
		{
			System.err.println("cannot close, database connection is null");
		}
	}

	private boolean initdb()
	{
		System.out.println("init database...");
		try
		{
			Class.forName("org.h2.Driver");
			this.dbcon = DriverManager.getConnection("jdbc:h2:"+this.homepath+"/"+this.dbname+"_"+this.sourcepath.hashCode(), "sa", "");
			System.out.println("database meta:'"+this.dbcon.getMetaData()+"'");
		} catch (ClassNotFoundException e)
		{
			System.err.println(e+" databasedriver not found, this should not happen oO");
			e.printStackTrace();
			return false;
		} catch (SQLException e)
		{
			System.err.println(e+" could not init database :(");
			e.printStackTrace();			
			return false;
		}
		return true;
	}
}
