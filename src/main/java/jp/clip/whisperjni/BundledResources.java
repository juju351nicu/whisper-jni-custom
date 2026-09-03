package jp.clip.whisperjni;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;

/**
 * jar に同梱したリソース（ネイティブライブラリ、VAD モデル）をディスクへ取り出すユーティリティ。
 *
 * <p>
 * JNI の {@link System#load(String)} は実ファイルしか受け付けないため、jar の中身を一時ディレクトリへ
 * コピーしてから読み込みます。IDE から実行しているときのように jar に入っていない場合は、
 * クラスパス上のディレクトリからそのままコピーします。
 * </p>
 *
 * <pre>
 * Path vadModel = Files.createTempFile("vad", ".bin");
 * BundledResources.exportVadModel(logger, vadModel);
 * params.vadModelPath = vadModel.toString();
 * </pre>
 */
public final class BundledResources
{
	/** 同梱している VAD モデルのリソース名。 */
	public static final String VAD_MODEL_RESOURCE = "ggml-silero-v6.2.0.bin";

	private BundledResources()
	{
		// ユーティリティクラスなのでインスタンス化しない
	}

	/**
	 * 同梱の VAD モデル（silero v6.2.0）を指定パスへ書き出します。
	 *
	 * @param logger      経過を記録する SLF4J {@link Logger}
	 * @param destination 書き出し先のファイルパス
	 * @throws IOException リソースが見つからない、または書き出しに失敗した場合
	 */
	public static void exportVadModel(Logger logger, Path destination) throws IOException
	{
		logger.info("Extracting {} to {}", VAD_MODEL_RESOURCE, destination);
		try(InputStream model = openResource(VAD_MODEL_RESOURCE))
		{
			Files.copy(model, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 同梱のリソースディレクトリを新しい一時ディレクトリへ丸ごと取り出します。
	 *
	 * @param logger       経過を記録する SLF4J {@link Logger}
	 * @param resourceName リソースディレクトリ名（例 {@code "windows-x64"}）
	 * @return 取り出し先の一時ディレクトリ
	 * @throws IOException リソースが見つからない、または取り出しに失敗した場合
	 */
	public static Path extractDirectory(Logger logger, String resourceName) throws IOException
	{
		Path destination = Files.createTempDirectory("whisper-jni-");
		extractDirectory(logger, resourceName, destination);
		return destination;
	}

	/**
	 * 同梱のリソースディレクトリを指定ディレクトリへ丸ごと取り出します。
	 *
	 * @param logger       経過を記録する SLF4J {@link Logger}
	 * @param resourceName リソースディレクトリ名（例 {@code "windows-x64"}）
	 * @param destination  取り出し先ディレクトリ
	 * @throws IOException リソースが見つからない、または取り出しに失敗した場合
	 */
	public static void extractDirectory(Logger logger, String resourceName, Path destination) throws IOException
	{
		URI uri = resourceUri(resourceName);
		logger.info("Extracting resource {} to {} (os.name={}, os.arch={})", uri, destination, Platform.OS_NAME, Platform.OS_ARCH);

		Path source = resolveResourcePath(logger, uri);
		try(Stream<Path> entries = Files.walk(source))
		{
			entries.forEach(entry -> copyEntry(source, entry, destination));
		}
		catch(UncheckedIOException e)
		{
			throw e.getCause();
		}
	}

	// ------------------------------------------------------------------------
	// 内部
	// ------------------------------------------------------------------------

	private static InputStream openResource(String resourceName) throws IOException
	{
		// getClassLoader() 経由で取得する。Class#getResource はパッケージを起点にしてしまう
		InputStream stream = BundledResources.class.getClassLoader().getResourceAsStream(resourceName);
		if(stream == null)
		{
			throw new IOException("Bundled resource not found: " + resourceName);
		}
		return stream;
	}

	private static URI resourceUri(String resourceName) throws IOException
	{
		URL url = BundledResources.class.getClassLoader().getResource(resourceName);
		if(url == null)
		{
			throw new IOException("Bundled resource not found: " + resourceName);
		}
		try
		{
			return url.toURI();
		}
		catch(URISyntaxException e)
		{
			throw new IOException(e);
		}
	}

	/**
	 * リソースの URI を、{@link Files} で歩ける {@link Path} に変換します。
	 * jar の中なら zip ファイルシステムを開き、通常のディレクトリならそのまま使います。
	 */
	private static Path resolveResourcePath(Logger logger, URI uri) throws IOException
	{
		if(!"jar".equals(uri.getScheme()))
		{
			logger.info("Resource is not inside a jar (scheme: {}), using it directly", uri.getScheme());
			return Path.of(uri);
		}

		// jar:file:/path/to/lib.jar!/windows-x64 → jar 本体の URI と内部パスに分ける
		String[] parts = uri.toString().split("!", 2);
		URI jarUri = URI.create(parts[0]);
		String pathInsideJar = parts[1];

		FileSystem jarFileSystem;
		try
		{
			logger.debug("Opening jar file system {}", jarUri);
			jarFileSystem = FileSystems.newFileSystem(jarUri, Map.of());
		}
		catch(FileSystemAlreadyExistsException e)
		{
			logger.debug("Jar file system already open, reusing it");
			jarFileSystem = FileSystems.getFileSystem(jarUri);
		}
		return jarFileSystem.getPath(pathInsideJar);
	}

	private static void copyEntry(Path sourceRoot, Path entry, Path destinationRoot)
	{
		// zip ファイルシステムの Path と既定ファイルシステムの Path は resolve できないので文字列で渡す
		Path target = destinationRoot.resolve(sourceRoot.relativize(entry).toString());
		try
		{
			if(Files.isDirectory(entry))
			{
				Files.createDirectories(target);
			}
			else
			{
				Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch(IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
