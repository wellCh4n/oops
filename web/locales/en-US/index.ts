import apps from "./apps"
import cmd from "./cmd"
import common from "./common"
import domains from "./domains"
import env from "./env"
import ide from "./ide"
import lang from "./lang"
import login from "./login"
import nav from "./nav"
import nodes from "./nodes"
import ns from "./ns"
import pipelines from "./pipelines"
import sidebar from "./sidebar"
import users from "./users"
import validation from "./validation"

const translations: Record<string, string> = {
  ...apps,
  ...cmd,
  ...common,
  ...domains,
  ...env,
  ...ide,
  ...lang,
  ...login,
  ...nav,
  ...nodes,
  ...ns,
  ...pipelines,
  ...sidebar,
  ...users,
  ...validation,
}

export default translations
