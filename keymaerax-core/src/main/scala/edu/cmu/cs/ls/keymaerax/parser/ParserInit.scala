/**
  * Copyright (c) Carnegie Mellon University.
  * See LICENSE.txt for the conditions of this license.
  */
package edu.cmu.cs.ls.keymaerax.parser

import edu.cmu.cs.ls.keymaerax.Configuration

/** Initializes the parser. */
object ParserInit {
  /** Initializes the parser from configuration. */
  def fromConfig(): Parser = Configuration.getString(Configuration.Keys.PARSER) match {
    case Some("KeYmaeraXParser") | None => KeYmaeraXParser.parser
    case Some("DLParser") => DLParser
    case Some(parserId) => throw new IllegalArgumentException("Unknown parser " + parserId + "; please use one of DLParser or KeYmaeraXParser")
  }
}
