import gov.doe.jgi.pi.pps.clarity.grails.RoutingService
import gov.doe.jgi.pi.pps.clarity.jgi.scripts.grails.ProcessRegistrationService
import gov.doe.jgi.pi.pps.clarity.jgi.scripts.model.process.ProcessFactory
import gov.doe.jgi.pi.pps.clarity.model.process.ActionHandler
import gov.doe.jgi.pi.pps.clarity.model.process.ClarityProcess
import gov.doe.jgi.pi.pps.clarity.model.researcher.Researcher
import gov.doe.jgi.pi.pps.clarity.model.researcher.ResearcherFactory

//import gov.doe.jgi.pi.pps.clarity.model.process.ProcessFactory

import gov.doe.jgi.pi.pps.clarity.web_transaction.ClarityWebTransaction
import gov.doe.jgi.pi.pps.clarity_node_manager.node.*
import gov.doe.jgi.pi.pps.clarity_node_manager.node.step.EppTrigger
import gov.doe.jgi.pi.pps.util.exception.WebException
import gov.doe.jgi.pi.pps.webtransaction.exception.MessageResolver
import gov.doe.jgi.pi.pps.webtransaction.util.ExternalId
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import grails.util.GrailsUtil
import org.apache.log4j.MDC
import org.grails.web.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder

@Transactional
class ProcessExecutionService {

    RoutingService routingService
    GrailsApplication grailsApplication
    MessageSource messageSource
    ProcessRegistrationService processRegistrationService

    static Logger logger = LoggerFactory.getLogger(ProcessExecutionService.class.name)

    void processScriptParams() {
        ClarityWebTransaction webTransaction = ClarityWebTransaction.requireCurrentTransaction()
        JSONObject submission = (JSONObject) webTransaction.jsonSubmission
        webTransaction.logger.info "submission:\n${submission.toString(2)}"

        def optionsJson = webTransaction.jsonSubmission.'options'
        JSONObject options = null
        try {
            options = (JSONObject) optionsJson
        } catch (Throwable t) {
            throw new WebException([code:'options.objectExpected', args:[optionsJson]],422,t)
        }
        if (!options) {
            throw new WebException([code:'options.empty', args:[optionsJson]],422)
        }

        def processId = options.'P'
        def stepUrl = options.'S'
        String action = options.'A'
        def fileIds = options.'L'

        if (!processId) {
            throw new WebException([code:'processId.notDefined', args:['P']],422)
        }
        webTransaction.externalId = new ExternalId(type:'clarity-process',id:processId)
        JSONObject data = new JSONObject()
        webTransaction.jsonResponse.'data' = data

        if (!stepUrl) {
            throw new WebException([code: 'stepUrl.notDefined', args: ['S']], 422)
        }

        List<String> fileLimsIds = []
        if (fileIds) {
            if (fileIds instanceof Collection) {
                fileLimsIds += fileIds*.toString()
            } else {
                fileLimsIds << (String) (fileIds as String)
            }
        }

        NodeManager nodeManager = webTransaction.requireNodeManager()
        ProcessNode processNode = nodeManager.getProcessNode(processId as String)

        if(!runScript(processNode)){//To not run scripts during migration
            log.info "Not executing action ${action}"
            webTransaction.statusCode = 200
            return
        }

        ClarityProcess clarityProcess = ProcessFactory.processInstance(processNode)
        Researcher researcher = ResearcherFactory.researcherForLimsId(nodeManager,processNode.technicianId)

        try {
            MDC.put('clarity-process-id',processId)

            if (researcher) {
                data.'researcher' = researcher.toJson()
                webTransaction.submittedBy = researcher.contactId
                MDC.put('clarity-user-contact-id',researcher.contactId)
            }

            webTransaction.logger.info "process ID: ${processId}; step URL: ${stepUrl}; action: ${action}; file LIMS IDs: ${fileLimsIds}"

            logger.info "process type: [${processNode.processType}]"
            logger.info "ClarityProcess: ${clarityProcess}"

            StepConfigurationNode stepConfigurationNode = processNode.stepsNode?.stepConfigurationNode
            EppTrigger completeTrigger = stepConfigurationNode.completeTrigger
            EppTrigger beginningTrigger = stepConfigurationNode.beginningTrigger
            log.info "StepConfigurationNode\n${stepConfigurationNode.nodeString}"

            if (stepConfigurationNode.eppTriggers && !completeTrigger) {
                throw new RuntimeException("clarity configuration error: step configuration [${stepConfigurationNode.url}] has triggers but no complete trigger")
            }
            if (stepConfigurationNode.eppTriggers && !beginningTrigger) {
                throw new RuntimeException("clarity configuration error: step configuration [${stepConfigurationNode.url}] has triggers but no beginning trigger")
            }
            ActionHandler actionHandler = clarityProcess.getActionHandler(action)
            data['action-handler'] = actionHandler.class.simpleName
            data['process-class'] = actionHandler.process.class.simpleName
            logger.info "ActionHandler class: \"${actionHandler.class.simpleName}\""
            logger.info "Process class: \"${actionHandler.process.class.simpleName}\""
            if (!actionHandler) {
                throw new RuntimeException("no handler registered for process type [${processNode.processType}], process class [${clarityProcess.class.name}], and action [${action}]")
            }

            boolean lastAction = actionHandler.lastAction
            if (completeTrigger) {
                logger.info "Complete Trigger name ${completeTrigger.name}"
                logger.info "Action handler name ${actionHandler.action}"
                lastAction = completeTrigger.name == actionHandler.action
            }
            webTransaction.jsonResponse.'last-action' = lastAction
            data['last-action'] = lastAction
            if (!lastAction) {
                nodeManager.readOnly = true
                List<ArtifactNodeInterface> outputArtifactNodes = clarityProcess.outputAnalytes*.artifactNode
                List<ContainerNode> outputContainer = nodeManager.getContainerNodes(outputArtifactNodes*.containerId)
                outputArtifactNodes*.readOnly = false
                outputContainer*.readOnly = false
            }
            //logArtifacts(webTransaction,actionHandler,nodeManager)

            actionHandler.options = options
            actionHandler.stepUrl = stepUrl as String
            actionHandler.fileIds = fileLimsIds

            log.info "Starting action ${actionHandler.class.name}"
            actionHandler.doExecute()
            nodeManager.httpPutDirtyNodes()
            logger.info "Is last action handler: $lastAction"
            if (lastAction) {
                logger.info "Submitting all routing requests..."
                routingService.submitRoutingRequests(clarityProcess.routingRequests)
            }
            webTransaction.statusCode = 200
        } catch(Throwable t) {
            GrailsUtil.sanitizeRootCause(t)
            String errorMessage = MessageResolver.exceptionToString(messageSource, LocaleContextHolder.getLocale(), t)
            logger.error "posting error message: ${errorMessage}"
            try {
                nodeManager.getProgramStatusNode(processNode.id)?.postErrorMsg(errorMessage)
            } catch (Throwable t2) {
                GrailsUtil.sanitizeRootCause(t2)
                logger.error "error posting error message: ${errorMessage}", t2
            }
            throw t
        } finally {
            MDC.clear()
        }
    }

    boolean runScript(ProcessNode processNode){
        if(processNode.technicianFullName == grailsApplication.config.migrationUser) {
            return false
        }
        return true
    }

    /*
    http://frow.jgi-psf.org:8080/api/v2/configuration/protocols/361
    <epp-triggers>
       <epp-trigger status="STEP_SETUP" point="BEFORE" type="AUTOMATIC" name="Place Samples" />
       <epp-trigger status="PLACEMENT" point="BEFORE" type="AUTOMATIC" name="Prepare Aliquot Creation Worksheet" />
       <epp-trigger status="RECORD_DETAILS" point="AFTER" type="AUTOMATIC" name="Process Aliquot Creation Worksheet" />
       <epp-trigger status="COMPLETE" point="BEFORE" type="AUTOMATIC" name="Route to Next Workflow" />
       <epp-trigger status="STARTED" point="AFTER" type="AUTOMATIC" name="Validate Input Analytes" />
    </epp-triggers>
    */
    /*
    users can change the output containers on PLACEMENT screen
    if the user clicked "NextSteps" on PLACEMENT screen or before entering RECORD_DETAILS then refresh outputs nodes
    users can update some process udfs and delete/upload a worksheet multiple times on the RECORD_DETAILS/STEP_SETUP screens
    if the user clicked "NextSteps" on RECORD_DETAILS/STEP_SETUP screen then refresh process node
    */
    /*
    void refreshNodes(ProcessNode processNode, def action) {
        ArtifactNode artifactNode = processNode.inputAnalytes[0]
        ProtocolNode protocolNodeInProgress = artifactNode.getProtocolNodeInProgress(processNode)
        def triggers = protocolNodeInProgress?.getEppTriggers()
        def currentTrigger = triggers.find { it.@'name' == action}
        if (!triggers || !currentTrigger) {
            def message = """
                Analyte $artifactNode.id: cannot find the '$action' trigger.
                Please check Clarity Configuration. The action( -A) must have the same name as the trigger.
            """
            ClarityWebTransaction.logger.warn(message)
            ProgramStatusNode programStatusNode = ClarityWebTransaction.requireCurrentTransaction().requireNodeManager().getProgramStatusNode(processNode.id)
            programStatusNode?.postErrorMsg(message)
        }
        def manual = currentTrigger.@'type' == 'MANUAL'
        def triggerRecordDetailsBefore = currentTrigger.@'status' == 'RECORD_DETAILS' && currentTrigger.@'point' == 'BEFORE'
        def triggerRecordDetailsAfter = currentTrigger.@'status' == 'RECORD_DETAILS' && currentTrigger.@'point' == 'AFTER'
        def triggerPlacementAfter = currentTrigger.@'status' == 'PLACEMENT' && currentTrigger.@'point' == 'AFTER'
        def triggerStepSetupAfter = currentTrigger.@'status' == 'STEP_SETUP' && currentTrigger.@'point' == 'AFTER'

        if(triggerStepSetupAfter) {
            ClarityWebTransaction.logger.info "Process Node $processNode.id: call processNode.httpRefresh()"
            processNode.clearCache()
        }
        if(manual || triggerRecordDetailsBefore || triggerPlacementAfter) {
            processNode.outputAnalytes.each{
                ClarityWebTransaction.logger.info "Artifact Node $it.id: call it.httpRefresh()"
                it?.httpRefresh()
            }
        }
        if(manual || triggerRecordDetailsAfter) {
            ClarityWebTransaction.logger.info "Process Node $processNode.id: call processNode.httpRefresh()"
            processNode.clearCache()
            processNode.httpRefresh()

            processNode.outputAnalytes.each{
                ClarityWebTransaction.logger.info "Artifact Node $it.id: call it.httpRefresh()"
                it?.httpRefresh()
            }
        }
    }
    */

}
