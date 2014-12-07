import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Arrays;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.PasswordProvider;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 
 */

/**
 * @author alex
 *
 */
public class TikaMetadata
{
	private ParseContext context;
	private Detector detector;
	private Parser parser;
	private String password = System.getenv("TIKA_PASSWORD");

	public TikaMetadata()
	{
		context = new ParseContext();
		detector = new DefaultDetector();
		parser = new AutoDetectParser(detector);
		context.set(Parser.class, parser);
		context.set(PasswordProvider.class, new PasswordProvider()
		{
			public String getPassword(Metadata metadata)
			{
				return password;
			}
		});
	}

	private class NoDocumentMetHandler extends DefaultHandler
	{
		protected final Metadata metadata;
		protected PrintWriter writer;
		private boolean metOutput;

		@SuppressWarnings("unused")
		public NoDocumentMetHandler(Metadata metadata, PrintWriter writer)
		{
			this.metadata = metadata;
			this.writer = writer;
			this.metOutput = false;
		}

		@Override
		public void endDocument()
		{
			String[] names = metadata.names();
			Arrays.sort(names);
			outputMetadata(names);
			writer.flush();
			this.metOutput = true;
		}

		public void outputMetadata(String[] names)
		{
			for (String name : names)
			{
				for (String value : metadata.getValues(name))
				{
					writer.println(name + ": " + value);
				}
			}
		}

		public boolean metOutput()
		{
			return this.metOutput;
		}
	}

	private class OutputType
	{

		public void process(InputStream input, OutputStream output,	Metadata metadata) throws Exception
		{
			Parser p = parser;

			ContentHandler handler = getContentHandler(output, metadata);
			p.parse(input, handler, metadata, context);
			// fix for TIKA-596: if a parser doesn't generate
			// XHTML output, the lack of an output document prevents
			// metadata from being output: this fixes that
			if (handler instanceof NoDocumentMetHandler)
			{
				NoDocumentMetHandler metHandler = (NoDocumentMetHandler) handler;
				if (!metHandler.metOutput())
				{
					metHandler.endDocument();
				}
			}
		}

		protected ContentHandler getContentHandler(OutputStream output,	Metadata metadata) throws Exception
		{
			throw new UnsupportedOperationException();
		}
	}

    private final OutputType NO_OUTPUT = new OutputType() {
        @Override
        protected ContentHandler getContentHandler(
                OutputStream output, Metadata metadata) {
            return new DefaultHandler();
        }
    };
    private OutputType type = NO_OUTPUT;
    
    public Metadata getmetadata(String path2url)
    {
    	URL url = null;
		File file = new File(path2url);
		InputStream input = null;
		Metadata metadata = new Metadata();
		
		try
		{
			url = file.toURI().toURL();

			input = TikaInputStream.get(url, metadata);

			this.type.process(input, System.out, metadata);

		} catch (IOException e)
		{
			System.err.println(e+" failed to read from filesystem :(");
			//e.printStackTrace();
		} catch (TikaException e)
		{
			System.err.println(e+" failed to parse file :(");
			//e.printStackTrace();
		} catch (Exception e)
		{
			System.err.println(e+" something badly failed :o");
			e.printStackTrace();
		} finally
		{
			try
			{
				input.close();
			} catch (IOException e)
			{
				System.err.println(e+" failed to close input :/");
				e.printStackTrace();
			}
			System.out.flush();
		}
		
		return metadata;
    }
}
