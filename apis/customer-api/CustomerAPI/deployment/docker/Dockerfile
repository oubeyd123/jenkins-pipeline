ARG BASE_IMAGE=wso2/wso2mi:latest
FROM ${BASE_IMAGE}

ARG WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0

COPY target/*.car ${WSO2_SERVER_HOME}/repository/deployment/server/carbonapps/
COPY libs/ ${WSO2_SERVER_HOME}/lib/

EXPOSE 8290 8253 9164
USER wso2carbon
