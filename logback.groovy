import ch.qos.logback.core.spi.LifeCycle
import grails.util.BuildSettings
import grails.util.Environment
import net.logstash.logback.encoder.LogstashEncoder
import org.grails.web.json.JSONObject
import org.springframework.boot.logging.logback.ColorConverter
import org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter

import java.nio.charset.Charset

conversionRule 'clr', ColorConverter
conversionRule 'wex', WhitespaceThrowableProxyConverter

// See http://logback.qos.ch/manual/groovy.html for details on configuration

String defaultPattern = //"%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ} [%thread] %-5level %logger{5} - %msg%n"
        '%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} ' + // Date
                '%clr(%5p) ' + // Log level
                '%clr(---){faint} %clr([%15.15t]){faint} ' + // Thread
                '%clr(%-40.40logger{39}){cyan} %clr(:){faint} ' + // Logger
                '%m%n%wex' // Message


appender('STDOUT', ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        charset = Charset.forName('UTF-8')
        pattern =
                '%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} ' + // Date
                        '%clr(%5p) ' + // Log level
                        '%clr(---){faint} %clr([%15.15t]){faint} ' + // Thread
                        '%clr(%-40.40logger{39}){cyan} %clr(:){faint} ' + // Logger
                        '%m%n%wex' // Message
    }
}


appender("ROLLER", RollingFileAppender) {
    file =  "${System.getProperty('user.home')}/logs/clarity-scripts.log"
    rollingPolicy(FixedWindowRollingPolicy) {
        fileNamePattern = "${System.getProperty('user.home')}/logs/clarity-scripts.%i.log"
        minIndex = 1
        maxIndex = 99
    }
    triggeringPolicy(SizeBasedTriggeringPolicy) {
        maxFileSize = "10MB"
    }
    encoder(PatternLayoutEncoder) {
        pattern = defaultPattern
    }
}

appender('JSON', RollingFileAppender) {
    file =  "${System.getProperty('user.home')}/json-logs/clarity-scripts-json.log"
    rollingPolicy(FixedWindowRollingPolicy) {
        fileNamePattern = "${System.getProperty('user.home')}/json-logs/clarity-scripts-json.%i.log"
        minIndex = 1
        maxIndex = 99
    }
    triggeringPolicy(SizeBasedTriggeringPolicy) {
        maxFileSize = "10MB"
    }
    encoder(LogstashEncoder) {
        timeZone = 'UTC'
        JSONObject customFieldsJson = new JSONObject()
        customFieldsJson['application-name'] = 'clarity-scripts'
        customFields = customFieldsJson.toString()
    }
}


def targetDir = BuildSettings.TARGET_DIR
if (Environment.isDevelopmentMode() && targetDir != null) {
    appender("FULL_STACKTRACE", FileAppender) {
        file = "${targetDir}/stacktrace.log"
        append = true
        encoder(PatternLayoutEncoder) {
            pattern = "%level %logger - %msg%n"
        }
    }
    logger("StackTrace", ERROR, ['FULL_STACKTRACE'], false)
}
root INFO, ['STDOUT','ROLLER','JSON']

