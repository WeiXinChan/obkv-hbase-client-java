# OBKV HBase Client
OBKV HBase Client is Java Library that can be used to access data from [OceanBase](https://github.com/oceanbase/oceanbase) by [HBase-1.x API](https://javadoc.io/doc/org.apache.hbase/hbase-client/1.3.6/index.html) or [Hbase-2.x API](https://javadoc.io/doc/org.apache.hbase/hbase-client/2.1.10/index.html).

## Quick start

Create table in the OceanBase database:

``` sql
CREATE TABLEGROUP test1;
CREATE TABLE `test1$family1` (
    `K` varbinary(1024) NOT NULL,
    `Q` varbinary(256) NOT NULL,
    `T` bigint(20) NOT NULL,
    `V` varbinary(1024) DEFAULT NULL,
    PRIMARY KEY (`K`, `Q`, `T`))
TABLEGROUP =  test1;
```
**Note:**
* test1: HBase table name;
* family1: HBase column family name.

Import the dependency for your maven project:
``` xml
<dependency>
    <groupId>com.oceanbase</groupId>
    <artifactId>obkv-hbase-client</artifactId>
    <version>1.3.0</version>
</dependency>
```
**Note:**
* This example version is not kept up-to-date. So check the [releases](https://github.com/oceanbase/obkv-hbase-client-java/releases) page for the latest version frequently.

The code demo:
``` java
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;

import static org.apache.hadoop.hbase.util.Bytes.toBytes;

public class SimpleHBaseClientDemo {
    public static void simpleTest() throws Exception {
        // 1. initial connection for table test1
        HBaseConfiguration conf = new HBaseConfiguration();
        Connection connection = ConnectionFactory.createConnection(conf);
        TableName tableName = TableName.valueOf("test1");
        Table hTable = connection.getTable(tableName);

        // 2. put data like hbase
        byte[] family = toBytes("family1");
        byte[] rowKey = toBytes("rowKey1");
        byte[] column = toBytes("column1");
        Put put = new Put(rowKey);
        put.add(family, column, System.currentTimeMillis(), toBytes("value1"));
        hTable.put(put);

        // 3. get data like hbase
        Get get = new Get(rowKey);
        get.addColumn(family, column);
        Result r = hTable.get(get);
        System.out.println("column1: " + r.getColumn(family, column));

        // 4. close
        hTable.close();
        connection.close();
    }

    public static void main(String[] args) throws Exception {
        simpleTest();
    }
}
```

The Hbase Configuration in hbase-site.xml for direct-connect mode:
```xml
<configuration>
    <property>
        <name>hbase.client.connection.impl</name>
        <value>com.alipay.oceanbase.hbase.util.OHConnectionImpl</value>
    </property>
    <property>
        <name>hbase.oceanbase.fullUserName</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.password</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.paramURL</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.sysUserName</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.sysPassword</name>
        <value></value>
    </property>
</configuration>
```

The Hbase Configuration in hbase-site.xml for ODP mode:
```xml
<configuration>
    <property>
        <name>hbase.client.connection.impl</name>
        <value>com.alipay.oceanbase.hbase.util.OHConnectionImpl</value>
    </property>
    <property>
        <name>hbase.oceanbase.odpMode</name>
        <value>true</value>
    </property>
    <property>
        <name>hbase.oceanbase.fullUserName</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.password</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.odpAddr</name>
        <value></value>
    </property>
    <property>
        <name>hbase.oceanbase.odpPort</name>
        <value>3307</value>
    </property>
    <property>
        <name>hbase.oceanbase.database</name>
        <value></value>
    </property>
</configuration>
```

**NOTE:**
* `hbase.client.connection.impl`: the implementation of hbase connenction, which must be set to `com.alipay.oceanbase.hbase.util.OHConnectionImpl` 
* `hbase.oceanbase.odpMode`: true indicate is in ODP mode, false(in default) indicate is in direct-connect mode
* `hbase.oceanbase.fullUserName`: the user for accessing obkv, which format is user_name@tenant_name#cluster_name 
* `hbase.oceanbase.password`: the password associated with the specified user
* `hbase.oceanbase.paramURL`: which is generated by [ConfigServer](https://ask.oceanbase.com/t/topic/35601923)
* `hbase.oceanbase.sysUserName`: root@sys or proxy@sys, which have privileges to access routing system view
* `hbase.oceanbase.sysPassword`:  the password associated with the specified sys user
* `hbase.oceanbase.odpAddr`: the ODP's IP address
* `hbase.oceanbase.odpPort`: the ODP's OBKV port
* `hbase.oceanbase.database`: the target database to operate on
 
## Documentation
- English [Coming soon]
- [Simplified Chinese (简体中文)](https://www.oceanbase.com/docs/common-oceanbase-database-cn-1000000002022354)

## Licencing

OBKV HBase Client is under [MulanPSL - 2.0](http://license.coscl.org.cn/MulanPSL2) licence. You can freely copy and use the source code. When you modify or distribute the source code, please obey the MulanPSL - 2.0 licence.

## Contributing

Contributions are warmly welcomed and greatly appreciated. Here are a few ways you can contribute:

- Raise us an [Issue](https://github.com/oceanbase/obkv-hbase-client-java/issues)
- Submit Pull Requests. For details, see [How to contribute](CONTRIBUTING.md).

## Support

In case you have any problems when using OceanBase Database, welcome reach out for help:

- GitHub Issue [GitHub Issue](https://github.com/oceanbase/obkv-hbase-client-java/issues)
- Official forum [Official website](https://open.oceanbase.com)
- Knowledge base [Coming soon]

