#!/bin/sh
#
# Copyright 2016-2020 The OpenZipkin Authors
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#

set -eux

# This script decides based on $RELEASE_VERSION whether to build or download the binaries we need.
if [ "$RELEASE_VERSION" = "master" ]
then
  echo "*** Building from source..."
  # Use the same command as we suggest in zipkin-server/README.md
  #  * Uses mvn not ./mvnw to reduce layer size: we control the Maven version in Docker
  (cd /code; mvn -T1C -q --batch-mode -DskipTests -Dlicense.skip=true --also-make -pl module/collector-sqs,module/collector-kinesis,module/storage-elasticsearch-aws,module/storage-xray clean package)
  cp /code/module/collector-sqs/target/zipkin-module-collector-sqs-*-module.jar sqs.jar
  cp /code/module/collector-kinesis/target/zipkin-module-collector-kinesis-*-module.jar kinesis.jar
  cp /code/module/storage-elasticsearch-aws/target/zipkin-module-storage-elasticsearch-aws-*-module.jar elasticsearch-aws.jar
  cp /code/module/storage-xray/target/zipkin-module-storage-xray-*-module.jar xray.jar
else
  echo "*** Downloading from Maven...."
  for artifact in collector-sqs collector-kinesis storage-elasticsearch-aws storage-xray; do
    # This prefers Maven central, but uses our release repository if it isn't yet synced.
    mvn --batch-mode org.apache.maven.plugins:maven-dependency-plugin:get \
        -DremoteRepositories=bintray::::https://dl.bintray.com/openzipkin/maven -Dtransitive=false \
        -Dartifact=io.zipkin.aws:zipkin-module-storage-${artifact}:${RELEASE_VERSION}:jar:module

    # Copy the module jar from the local Maven repository
    find ~/.m2/repository -name zipkin-module-${artifact}-${RELEASE_VERSION}-module.jar -exec cp {} ${artifact}.jar \;
  done

  mv collector-sqs.jar sqs.jar
  mv collector-kinesis.jar kinesis.jar
  mv storage-elasticsearch-aws.jar elasticsearch-aws.jar
  mv storage-xray.jar xray.jar
fi

# sanity check!
test -f sqs.jar
test -f kinesis.jar
test -f elasticsearch-aws.jar
test -f xray.jar

for module in sqs kinesis elasticsearch-aws xray; do
  (mkdir ${module} && cd ${module} && jar -xf ../${module}.jar) && rm ${module}.jar
done