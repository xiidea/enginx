plugins {
    alias(libs.plugins.spring.boot) apply false
}

// Every module is configured through the `enginx.java-conventions` plugin in buildSrc.
// Module dependency direction (enforced by ArchUnit in :management-domain):
//
//   domain  <-  application  <-  security
//      ^            ^               ^
//      |            |               |
//      +-- infrastructure           |
//      +-- api ---------------------+
//      +-- boot (wires everything together)
