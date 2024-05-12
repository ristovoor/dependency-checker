plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.javatuples:javatuples:1.2")
    implementation("commons-io:commons-io:2.11.0")
    //implementation("javax.xml.bind:jaxb-api:2.3.1")
}


tasks.test {
    useJUnitPlatform()
}