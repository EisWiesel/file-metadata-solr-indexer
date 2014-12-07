import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 
 */

/**
 * @author alex
 *
 */
class Fileadder extends SimpleFileVisitor<Path>
{
	private PreparedStatement prepaddstmt;
	
	public Fileadder(PreparedStatement prepaddstmt)
	{
		this.prepaddstmt = prepaddstmt;
	}
	
	// each file.
	@Override
	public FileVisitResult visitFile(Path file, BasicFileAttributes attr)
	{
		try
		{
			prepaddstmt.setString(1, file.toAbsolutePath().toString());
			prepaddstmt.addBatch();
			System.out.print(".");
		} catch (SQLException e)
		{
			System.err.println(e+" could not add '"+file.toAbsolutePath()+"' to prepared statement :(");
			e.printStackTrace();
		}
		return CONTINUE;
	}

	// each directory
	@Override
	public FileVisitResult postVisitDirectory(Path dir, IOException exc)
	{
		
		return CONTINUE;
	}	
	
	@Override
	public FileVisitResult visitFileFailed(Path file, IOException exc)
	{
		System.err.println(exc);
		return CONTINUE;
	}
}
