import java.io.File

import com.github.mrpowers.spark.daria.sql.EtlDefinition
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Dataset, Row, SparkSession}

case class Config(
                   source: String = "",
                   destination: String = "",
                   saveMode: String = "overwrite",
                   fileType: String = "",
                   readOptions: Map[String, String] = Map(),
                   limit: Int = -1,
                   tables: Seq[String] = Seq(),
                   destinationSchema: String = "",
                   destinationFileType: String = "",
                   writeOptions: Map[String, String] = Map()
                 )


case class Source(
                   sourceType: String,
                   path: String,
                   fileType: Option[String],
                   readOptions: Map[String, String],
                   tables: Seq[String],
                   limit: Int
                 )

case class Destination(
                        path: String,
                        destinationType: String,
                        schema: Option[String],
                        fileType: Option[String],
                        writeOptions: Map[String, String]
                      )

object Cli {

  val sourceCliText =
    """
                       Source is a source in the form of jdbc connection string, s3 or local file system
                       File:
                          file:///tmp/*/file.csv            // you can use wildcard here
                       S3:
                          s3://indicium-data/raw/*/somefile.json  // you can use wildcard here
                       if source is file type or s3, filetype must be provided
                       Jdbc alternatives and jdbc connections string example:
                          postgres:  jdbc:postgresql://<host>:<port>/<database>?user=<user>&password=<password>
                          oracle:    jdbc:oracle:thin:<user>/<password>@<host>:<port>
                          sqlserver: jdbc:sqlserver://<host>:<port>;database=<database>;user=<user>;password=<password>;

  """

  val conf = new SparkConf().set("spark.hadoop.mapreduce.fileoutputcommitter.algorithm.version", "2")
  val spark = SparkSession.builder.config(conf).appName("Spark SQL data mover")
    .master("local[*]").getOrCreate()

  import scopt.OParser

  val builder = OParser.builder[Config]
  val parser = {
    import builder._

    OParser.sequence(
      programName("Data Mover"),
      head("Data mover", "1.0", "Move data from any source to any destination that spark sql supports"),

      opt[String]('s', "source")
        .required()
        .action((source, c) => c.copy(source = source))
        .text(sourceCliText),

      opt[String]('t', "filetype")
        .action((fileType, c) => c.copy(fileType = fileType))
        .text("If source is file or s3, filetype must be specified, one of [json,csv,parquet,orc]"),

      opt[Int]("limit")
        .action((limit, c) => c.copy(limit = limit))
        .text("Number of rows to extract"),

      opt[Map[String, String]]("read-options")
        .action((readOptions, c) => c.copy(readOptions = readOptions))
        .text("example for csv: --read_options sep=';' header=true..."),

      opt[String]("destination")
        .required()
        .action((dest, c) => c.copy(destination = dest))
        .text("Destination of the data, can be the same of the source or 'console' for printing the output to the console"),

      opt[Seq[String]]("tables")
        .action((tables, c) => c.copy(tables = tables))
        .text("Tables to read, if jdbc source defined this is required"),

      opt[String]("destination-schema")
        .action((destSchema, c) => c.copy(destinationSchema = destSchema))
        .text("Schema to write the table at, if none is specified will be write to default, usually public"),

      opt[String]("destination-filetype")
        .action((destFileType, c) => c.copy(destinationFileType = destFileType))
        .text("Type of file to write one of [csv, parquet, json, orc]. If destination is of file this is argument is required"),

      opt[Map[String, String]]("write-options")
        .action((writeOptions, c) => c.copy(writeOptions = writeOptions))
        .text("spark options for writing, example for csv: --write_options sep=';' header=true..., for more options read the docs")

    )
  }

  def main(args: Array[String]): Unit = {
    OParser.parse(parser, args, Config()) match {
      case Some(config) =>
        run(config)
      case _ =>
      // arguments are bad, error message will have been displayed
    }
  }

  def validateArgsCombinations(source: Source, config: Config) = {
    if (Seq("file", "s3").contains(source.sourceType) && config.fileType == "")
      throw new IllegalArgumentException("If file or s3 source is specified, file type must be provided")

    if (config.destination.startsWith("file") && config.destinationFileType == "")
      throw new IllegalArgumentException("If destination is of type file, destination-filetype must be provided")

    if (source.sourceType == "jdbc" && config.tables.isEmpty)
      throw new IllegalArgumentException("If source is of type jdbc, tables arg most be provided ")

  }

  def run(config: Config): Unit = {
    val source = parseSource(config)
    val destination = parseDestination(config)
    validateArgsCombinations(source, config)

    val reader: Reader = source.sourceType match {
      case "file" => FileReader
      case "jdbc" => JdbcReader
      case _ => throw new IllegalArgumentException("Unsupported source " + source.sourceType)
    }

    val writer: Writer = config.destination match {
      case "console" => ConsoleWriter
      case dest if dest.startsWith("jdbc") => JdbcWriter
      case dest if dest.startsWith("file") => FileWriter
      case _ => throw new IllegalArgumentException("Unsupported destination " + config.destination)
    }

    val dfs = reader.read(spark, source)

    dfs.foreach(nameAndDf => {
      val name = nameAndDf._1
      val df = nameAndDf._2
      writer.write(name, df, destination)
    })
  }

  def parseDestination(config: Config): Destination = {
    val destination = config.destination

    val destinationType = destination match {
      case s if destination.startsWith("s3") => "s3"
      case s if destination.startsWith("jdbc") => "jdbc"
      case s if destination.startsWith("file") => "file"
      case _ => throw new IllegalArgumentException("Source must be of type s3, jdbc or file")
    }
    val writeOptions = config.writeOptions
    val fileType = if (config.destinationFileType == "") None else Some(config.destinationFileType)
    val schema = if (config.destinationSchema == "") None else Some(config.destinationSchema)

    Destination(
      destination,
      destinationType,
      schema,
      fileType,
      writeOptions
    )
  }

  def parseSource(config: Config): Source = {
    val source = config.source

    val sourceType = source match {
      case s if source.startsWith("s3") => "s3"
      case s if source.startsWith("jdbc") => "jdbc"
      case s if source.startsWith("file") => "file"
      case _ => throw new IllegalArgumentException("Source must be of type s3, jdbc or file")
    }

    val fileType = if (config.fileType == "") None else Some(config.fileType)
    val readOptions = config.readOptions
    val tables = config.tables
    val limit = config.limit

    Source(sourceType, source, fileType, readOptions, tables, limit)
  }
}
