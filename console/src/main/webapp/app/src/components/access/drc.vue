<template v-if="current === 0" :key="0">
  <div>
    <Alert :type="status" show-icon v-if="hasResp" style="width: 65%; margin-left: 250px">
      {{title}}
      <span slot="desc" v-html="message"></span>
    </Alert>
    <Row>
      <i-col span="12">
        <Form ref="drc1" :model="drc" :rules="ruleDrc" :label-width="250" style="float: left; margin-top: 50px">
          <FormItem label="源集群名" prop="oldClusterName" style="width: 600px">
            <Input v-model="drc.oldClusterName" @input="changeOld" placeholder="请输入源集群名"/>
          </FormItem>
          <FormItem label="选择Replicator" prop="replicator">
            <Select v-model="drc.replicators.old" multiple style="width: 200px" placeholder="选择源集群Replicator">
              <Option v-for="item in drc.replicatorlist.old" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem label="选择Applier" prop="applier">
            <Select v-model="drc.appliers.old" multiple style="width: 200px" placeholder  ="选择源集群Applier">
              <Option v-for="item in drc.applierlist.old" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem label="设置ApplierTargetName" prop="oldTargetName" style="width: 600px">
            <Input v-model="drc.oldTargetName" placeholder="请输入设置ApplierTargetName，不填默认为本侧dalcluster_name"/>
          </FormItem>
          <FormItem label="配置表过滤" prop="oldNameFilter" style="width: 600px">
            <Input v-model="drc.oldNameFilter" type="textarea" :autosize="true" placeholder="请输入表名，支持正则表达式，以逗号分隔，不填默认为全部表"/>
            <Button @click="checkMysqlTablesInOldMha">同步表校验</Button>
          </FormItem>
          <FormItem label="配置表名映射" prop="oldNameMapping" style="width: 600px">
            <Input v-model="drc.oldNameMapping" type="textarea" :autosize="true"
                   placeholder="请输入映射关系，如：srcDb1.srcTable1,destDb1.destTable1;srcDb2.srcTable2,destDb2.destTable2"/>
          </FormItem>
          <FormItem label="设置executedGtid" style="width: 600px">
            <Input v-model="drc.oldExecutedGtid" placeholder="请输入源集群executedGtid，不填自动获取本侧gtid"/>
            <Button @click="queryNewMhaMachineGtid">查询对侧gtid</Button>
            <span v-if="hasTest1">
                  <Icon :type="testSuccess1 ? 'ios-checkmark-circle' : 'ios-close-circle'"
                        :color="testSuccess1 ? 'green' : 'red'"/>
                    {{ testSuccess1 ? '连接查询成功' : '连接查询失败，请手动输入gtid' }}
            </span>
          </FormItem>
          <FormItem label="行过滤" style="width: 600px">
            <Button type="primary" ghost @click="goToConfigRowsFiltersInSrcApplier">配置行过滤</Button>
          </FormItem>
          <FormItem label="同步配置" style="width: 600px">
            <Button type="primary" ghost @click="goToConfigVosInSrcMha">同步表管理</Button>
          </FormItem>
          <FormItem label="设置applyMode" style="width: 600px">
            <Select v-model="drc.oldApplyMode" style="width:200px">
              <Option v-for="item in applyModeList" :value="item.value" :key="item.value">{{ item.label }}</Option>
            </Select>
          </FormItem>
        </Form>
      </i-col>
      <i-col span="12">
        <Form ref="drc2" :model="drc" :rules="ruleDrc" :label-width="250" style="float: left; margin-top: 50px">
          <FormItem label="新集群名" prop="newClusterName" style="width: 600px">
            <Input v-model="drc.newClusterName" @input="changeNew" placeholder="请输入新集群名"/>
          </FormItem>
          <FormItem label="选择Replicator" prop="replicator">
            <Select v-model="drc.replicators.new" multiple style="width: 200px" placeholder="选择新集群Replicator">
              <Option v-for="item in drc.replicatorlist.new" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem label="选择Applier" prop="applier">
            <Select v-model="drc.appliers.new" multiple style="width: 200px" placeholder="选择新集群Applier">
              <Option v-for="item in drc.applierlist.new" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem label="设置ApplierTargetName" prop="newTargetName" style="width: 600px">
            <Input v-model="drc.newTargetName" placeholder="请输入设置ApplierTargetName，不填默认为dalcluster_name"/>
          </FormItem>
          <FormItem label="配置表过滤" prop="newNameFilter" style="width: 600px">
            <Input v-model="drc.newNameFilter" type="textarea" :autosize="true" placeholder="请输入表名，支持正则表达式，以逗号分隔，不填默认为全部表"/>
            <Button @click="checkMysqlTablesInNewMha">同步表校验</Button>
          </FormItem>
          <FormItem label="配置表名映射" prop="newNameMapping" style="width: 600px">
            <Input v-model="drc.newNameMapping" type="textarea" :autosize="true"
                   placeholder="请输入映射关系，如：srcDb1.srcTable1,destDb1.destTable1;srcDb2.srcTable2,destDb2.destTable2"/>
          </FormItem>
          <FormItem label="设置executedGtid" style="width: 600px">
            <Input v-model="drc.newExecutedGtid" placeholder="请输入新集群executedGtid，不填自动获取本侧gtid"/>
            <Button @click="queryOldMhaMachineGtid">查询对侧gtid</Button>
            <span v-if="hasTest2">
                  <Icon :type="testSuccess2 ? 'ios-checkmark-circle' : 'ios-close-circle'"
                        :color="testSuccess2 ? 'green' : 'red'"/>
                    {{ testSuccess2 ? '连接查询成功' : '连接查询失败，请手动输入gtid' }}
                </span>
          </FormItem>
          <FormItem label="行过滤" style="width: 600px">
            <Button type="primary" ghost @click="goToConfigRowsFiltersInDestApplier">配置行过滤</Button>
          </FormItem>
          <FormItem label="同步配置" style="width: 600px">
            <Button type="primary" ghost @click="goToConfigVosInDestMha">同步表管理</Button>
          </FormItem>
          <FormItem label="设置applyMode" style="width: 600px">
            <Select v-model="drc.newApplyMode" style="width:200px">
              <Option v-for="item in applyModeList" :value="item.value" :key="item.value">{{ item.label }}</Option>
            </Select>
          </FormItem>
        </Form>
      </i-col>
    </Row>
    <Form :label-width="250" style="margin-top: 50px">
      <FormItem>
        <Button @click="handleReset('drc1');handleReset('drc2')">重置</Button>
        <br><br>
        <Button type="primary" @click="preCheckConfigure ()">提交</Button>
      </FormItem>
      <Modal
        v-model="drc.reviewModal"
        title="确认配置信息"
        width="900px"
        @on-ok="submitConfig">
        <Row :gutter="5">
          <i-col span="12">
            <Form style="width: 80%">
              <FormItem label="源集群名">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.oldClusterName" readonly/>
              </FormItem>
              <FormItem label="源集群端Replicator">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.replicators.old" readonly/>
              </FormItem>
              <FormItem label="源集群端Applier">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.appliers.old" readonly/>
              </FormItem>
              <FormItem label="源ApplierTargetName">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.oldTargetName" readonly/>
              </FormItem>
              <FormItem label="源集群端表过滤">
                <p v-if=" drc.oldNameFilter == null || drc.oldNameFilter === ''" style="color: #ff9900">同步全部表</p>
                <Input v-model="drc.oldNameFilter" type="textarea" :autosize="true" readonly/>
              </FormItem>
              <FormItem label="源集群端表名映射">
                <Input v-model="drc.oldNameMapping" type="textarea" :autosize="true" readonly/>
              </FormItem>
              <FormItem label="源集群端executedGtid">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.oldExecutedGtid" readonly/>
              </FormItem>
              <FormItem label="源集群端applyMode">
                <Select v-model="drc.oldApplyMode" disabled>
                  <Option v-for="item in applyModeList" :value="item.value" :key="item.value">{{ item.label }}</Option>
                </Select>
              </FormItem>
            </Form>
          </i-col>
          <i-col span="12">
            <Form style="width: 80%">
              <FormItem label="新集群名">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.newClusterName" readonly/>
              </FormItem>
              <FormItem label="新集群端Replicator">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.replicators.new" readonly/>
              </FormItem>
              <FormItem label="新集群端Applier">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.appliers.new" readonly/>
              </FormItem>
              <FormItem label="新ApplierTargetName">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.newTargetName" readonly/>
              </FormItem>
              <FormItem label="新集群端表过滤">
                <p v-if="drc.newNameFilter == null || drc.newNameFilter === ''" style="color: #ff9900">同步全部表</p>
                <Input v-model="drc.newNameFilter" type="textarea" :autosize="true" readonly/>
              </FormItem>
              <FormItem label="新集群端表名映射">
                <Input v-model="drc.newNameMapping" type="textarea" :autosize="true" readonly/>
              </FormItem>
              <FormItem label="新集群端executedGtid">
                <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="drc.newExecutedGtid" readonly/>
              </FormItem>
              <FormItem label="新集群端applyMode">
                <Select v-model="drc.newApplyMode" disabled>
                  <Option v-for="item in applyModeList" :value="item.value" :key="item.value">{{ item.label }}</Option>
                </Select>
              </FormItem>
            </Form>
          </i-col>
        </Row>
      </Modal>
      <Modal
        v-model="drc.resultModal"
        title="配置结果"
        width="1200px">
        <Form style="width: 100%">
          <FormItem label="集群配置">
            <Input type="textarea" :autosize="{minRows: 1,maxRows: 30}" v-model="result" readonly/>
          </FormItem>
        </Form>
      </Modal>
      <!--             v-model="drc.warnModal"-->
      <Modal
        v-model="drc.warnModal"
        title="存在一对多共用运行配置请确认"
        width="900px"
        @on-ok="reviewConfigure">
        <label style="color: black">共用replicator配置的集群: </label>
        <input v-model="drc.conflictMha"></input>
        <Divider/>
        <div>
          <p style="color: red">线上一对多replicator配置</p>
          <ul>
            <ol v-for="item in drc.replicators.share" :key="item">{{item}}</ol>
          </ul>
        </div>
        <Divider/>
        <div>
          <p style="color: black">修改后replicator配置</p>
          <ul>
            <ol v-for="item in drc.replicators.conflictCurrent" :key="item">{{item}}</ol>
          </ul>
        </div>
      </Modal>
    </Form>
    <Modal
      v-model="nameFilterCheck.modal"
      title="表检验"
      width="1000px">
      <Card>
        <div slot="title">
          <span>相关表</span>
        </div>
        <Table stripe :columns="nameFilterCheck.columns" :data="dataWithPage" border ></Table>
        <div style="text-align: center;margin: 16px 0">
          <Page
            :transfer="true"
            :total="nameFilterCheck.tableData.length"
            :current.sync="nameFilterCheck.current"
            :page-size-opts="nameFilterCheck.pageSizeOpts"
            :page-size="this.nameFilterCheck.size"
            show-total
            show-sizer
            show-elevator
            @on-page-size-change="handleChangeSize"></Page>
        </div>
      </Card>
    </Modal>
  </div>
</template>
<script>
export default {
  name: 'drc',
  props: {
    oldClusterName: String,
    newClusterName: String,
    env: String
  },
  data () {
    return {
      result: '',
      status: '',
      title: '',
      message: '',
      hasResp: false,
      hasTest1: false,
      testSuccess1: false,
      hasTest2: false,
      testSuccess2: false,
      applyModeList: [
        {
          value: 0,
          label: 'SET_GTID'
        },
        {
          value: 1,
          label: 'TRANSACTION_TABLE (default)'
        }
      ],
      drc: {
        reviewModal: false,
        warnModal: false,
        resultModal: false,
        oldClusterName: this.oldClusterName,
        newClusterName: this.newClusterName,
        oldTargetName: this.oldTargetName,
        newTargetName: this.newTargetName,
        oldNameFilter: '',
        oldNameMapping: '',
        newNameFilter: '',
        newNameMapping: '',
        oldExecutedGtid: '',
        newExecutedGtid: '',
        oldApplyMode: 1,
        newApplyMode: 1,
        env: this.env,
        needread: false,
        columns: [
          {
            type: 'selection',
            width: 60,
            align: 'center'
          },
          {
            title: 'DB名',
            key: 'name'
          }
        ],
        dbNames: [],
        selectedDbs: [],
        envList: [
          {
            value: 'product',
            label: 'PRODUCT'
          },
          {
            value: 'fat',
            label: 'FAT'
          },
          {
            value: 'lpt',
            label: 'LPT'
          },
          {
            value: 'uat',
            label: 'UAT'
          }
        ],
        replicatorlist: {
          old: [],
          new: []
        },
        conflictMha: '',
        replicators: {
          old: [],
          new: [],
          share: [],
          conflictCurrent: []
        },
        replicator: {},
        searchReplicatorIp: '',
        usedReplicatorPorts: '',
        applierlist: {
          old: [],
          new: []
        },
        appliers: {
          old: [],
          new: []
        },
        previewMeta: ''
      },
      ruleDrc: {
        oldClusterName: [
          { required: true, message: '源集群名不能为空', trigger: 'blur' }
        ],
        newClusterName: [
          { required: true, message: '新集群名不能为空', trigger: 'blur' }
        ],
        env: [
          { required: true, message: '环境不能为空', trigger: 'blur' }
        ],
        replicator: [
          { required: true, message: 'replicator不能为空', trigger: 'blur' }
        ],
        applier: [
          { required: true, message: 'applier不能为空', trigger: 'blur' }
        ]
      },
      nameFilterCheck: {
        modal: false,
        tableData: [],
        columns: [
          {
            title: '序号',
            width: 75,
            align: 'center',
            fixed: 'left',
            render: (h, params) => {
              return h(
                'span',
                params.index + 1
              )
            }
          },
          {
            title: '库名',
            key: 'schema'
          },
          {
            title: '表名',
            key: 'table'
          },
          {
            title: '无OnUpdate字段',
            key: 'noOnUpdateColumn',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const text = row.noOnUpdateColumn ? 'True' : ''
              return h('span', text)
            }
          },
          {
            title: '无OnUpdate字段索引',
            key: 'noOnUpdateKey',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const text = row.noOnUpdateKey ? 'True' : ''
              return h('span', text)
            }
          },
          {
            title: '无PkUk',
            key: 'noPkUk',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const text = row.noPkUk ? 'True' : ''
              return h('span', text)
            }
          },
          {
            title: '支持Truncate',
            key: 'approveTruncate',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const text = row.approveTruncate ? 'True' : ''
              return h('span', text)
            }
          },
          {
            title: '存在DefaultTime为0',
            key: 'timeDefaultZero',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const text = row.timeDefaultZero ? 'True' : ''
              return h('span', text)
            }
          },
          {
            title: '结果',
            width: 100,
            align: 'center',
            render: (h, params) => {
              const row = params.row
              const flag = row.noOnUpdateColumn || row.noOnUpdateKey || row.noPkUk || row.approveTruncate || row.timeDefaultZero
              const color = flag ? 'volcano' : 'green'
              const text = flag ? '错误' : '正常'
              return h('Tag', {
                props: {
                  color: color
                }
              }, text)
            }
          }
        ],
        total: 0,
        current: 1,
        size: 5,
        pageSizeOpts: [5, 10, 20, 100]
      }
    }
  },
  computed: {
    dataWithPage () {
      const data = this.nameFilterCheck.tableData
      const start = this.nameFilterCheck.current * this.nameFilterCheck.size - this.nameFilterCheck.size
      const end = start + this.nameFilterCheck.size
      return [...data].slice(start, end)
    }
  },
  methods: {
    handleReset (name) {
      this.$refs[name].resetFields()
    },
    getResourcesInOld () {
      this.axios.get('/api/drc/v1/meta/mhas/' + this.drc.oldClusterName + '/resources/all/types/R')
      // this.axios.get('/api/drc/v1/meta/resources?type=R')
        .then(response => {
          console.log(response.data)
          this.drc.replicatorlist.old = []
          response.data.data.forEach(ip => this.drc.replicatorlist.old.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/mhas/' + this.drc.oldClusterName + '/resources/all/types/A')
      // this.axios.get('/api/drc/v1/meta/resources?type=A')
        .then(response => {
          console.log(response.data)
          this.drc.applierlist.old = []
          response.data.data.forEach(ip => this.drc.applierlist.old.push(ip))
        })
    },
    getResourcesInUseInOld () {
      this.axios.get('/api/drc/v1/meta/resources/using/types/R?localMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' replicators ' + response.data.data)
          this.drc.replicators.old = []
          response.data.data.forEach(ip => this.drc.replicators.old.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/resources/using/types/A?localMha=' + this.drc.oldClusterName + '&remoteMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' request ' + this.drc.newClusterName + ' appliers ' + response.data.data)
          this.drc.appliers.old = []
          response.data.data.forEach(ip => this.drc.appliers.old.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/targetName?localMha=' + this.drc.oldClusterName + '&remoteMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' request ' + this.drc.newClusterName + ' targetName ' + response.data.data)
          this.drc.oldTargetName = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/namefilter?localMha=' + this.drc.oldClusterName + '&remoteMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' request ' + this.drc.newClusterName + ' namefilter ' + response.data.data)
          this.drc.oldNameFilter = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/namemapping?localMha=' + this.drc.oldClusterName + '&remoteMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' request ' + this.drc.newClusterName + ' namemapping ' + response.data.data)
          this.drc.oldNameMapping = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/applymode?localMha=' + this.drc.oldClusterName + '&remoteMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.oldClusterName + ' request ' + this.drc.newClusterName + ' applymode ' + response.data.data)
          this.drc.oldApplyMode = response.data.data
        })
    },
    getResourcesInNew () {
      this.axios.get('/api/drc/v1/meta/mhas/' + this.drc.newClusterName + '/resources/all/types/R')
      // this.axios.get('/api/drc/v1/meta/resources?type=R')
        .then(response => {
          console.log(response.data)
          this.drc.replicatorlist.new = []
          response.data.data.forEach(ip => this.drc.replicatorlist.new.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/mhas/' + this.drc.newClusterName + '/resources/all/types/A')
      // this.axios.get('/api/drc/v1/meta/resources?type=A')
        .then(response => {
          console.log(response.data)
          this.drc.applierlist.new = []
          response.data.data.forEach(ip => this.drc.applierlist.new.push(ip))
        })
    },
    getResourcesInUseInNew () {
      this.axios.get('/api/drc/v1/meta/resources/using/types/R?localMha=' + this.drc.newClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' replicators ' + response.data.data)
          this.drc.replicators.new = []
          response.data.data.forEach(ip => this.drc.replicators.new.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/resources/using/types/A?localMha=' + this.drc.newClusterName + '&remoteMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' request ' + this.drc.oldClusterName + ' appliers ' + response.data.data)
          this.drc.appliers.new = []
          response.data.data.forEach(ip => this.drc.appliers.new.push(ip))
        })
      this.axios.get('/api/drc/v1/meta/targetName?localMha=' + this.drc.newClusterName + '&remoteMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' request ' + this.drc.oldClusterName + ' targetName ' + response.data.data)
          this.drc.newTargetName = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/namefilter?localMha=' + this.drc.newClusterName + '&remoteMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' request ' + this.drc.oldClusterName + ' namefilter ' + response.data.data)
          this.drc.newNameFilter = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/namemapping?localMha=' + this.drc.newClusterName + '&remoteMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' request ' + this.drc.oldClusterName + ' namemapping ' + response.data.data)
          this.drc.newNameMapping = response.data.data
        })
      this.axios.get('/api/drc/v1/meta/applymode?localMha=' + this.drc.newClusterName + '&remoteMha=' + this.drc.oldClusterName)
        .then(response => {
          console.log(this.drc.newClusterName + ' request ' + this.drc.oldClusterName + ' applymode ' + response.data.data)
          this.drc.newApplyMode = response.data.data
        })
    },
    queryOldMhaMachineGtid () {
      const that = this
      console.log('/api/drc/v1/mha/gtid?mha=' + this.drc.oldClusterName)
      that.axios.get('/api/drc/v1/mha/gtid?mha=' + this.drc.oldClusterName)
        .then(response => {
          this.hasTest2 = true
          if (response.data.status === 0) {
            this.drc.newExecutedGtid = response.data.data
            this.testSuccess2 = true
          } else {
            this.testSuccess2 = false
          }
        })
    },
    queryNewMhaMachineGtid () {
      const that = this
      console.log('/api/drc/v1/mha/gtid?mha=' + this.drc.newClusterName)
      that.axios.get('/api/drc/v1/mha/gtid?mha=' + this.drc.newClusterName)
        .then(response => {
          this.hasTest1 = true
          if (response.data.status === 0) {
            this.drc.oldExecutedGtid = response.data.data
            this.testSuccess1 = true
          } else {
            this.testSuccess1 = false
          }
        })
    },
    debug () {
      console.log('replicators: ' + this.drc.replicatorlist)
      console.log('appliers: ' + this.drc.applierlist)
    },
    searchUsedReplicatorPorts () {
      this.axios.get('/api/drc/v1/meta/resources/ip/' + this.drc.searchReplicatorIp)
        .then(response => {
          console.log(response.data)
          this.drc.usedReplicatorPorts = []
          response.data.data.forEach(port => this.drc.usedReplicatorPorts.push(port))
        })
    },
    changeSelection () {
      this.drc.selectedDbs = this.$refs.selection.getSelection()
    },
    changeOld () {
      this.$emit('oldClusterChanged', this.drc.oldClusterName)
      this.getResourcesInOld()
      this.getResourcesInUseInOld()
    },
    changeNew () {
      this.$emit('newClusterChanged', this.drc.newClusterName)
      this.getResourcesInNew()
      this.getResourcesInUseInNew()
    },
    start () {
      this.$Loading.start()
    },
    finish () {
      this.$Loading.finish()
    },
    error () {
      this.$Loading.error()
    },
    changeModal (name) {
      this.$refs[name].validate((valid) => {
        if (!valid) {
          this.$Message.error('仍有必填项未填!')
        } else {
          this.drc.reviewModal = true
        }
      })
    },
    preCheckConfigure () {
      console.log('preCheck')
      this.axios.post('/api/drc/v1/meta/config/preCheck', {
        srcMha: this.drc.oldClusterName,
        destMha: this.drc.newClusterName,
        srcReplicatorIps: this.drc.replicators.old,
        srcApplierIps: this.drc.appliers.old,
        srcApplierIncludedDbs: null,
        srcApplierApplyMode: this.drc.oldApplyMode,
        srcGtidExecuted: this.drc.oldExecutedGtid,
        destReplicatorIps: this.drc.replicators.new,
        destApplierIps: this.drc.appliers.new,
        destApplierIncludedDbs: null,
        destApplierApplyMode: this.drc.newApplyMode,
        destGtidExecuted: this.drc.newExecutedGtid
      }).then(response => {
        const preCheckRes = response.data.data
        if (preCheckRes.status === 0) {
          // 无风险继续
          this.drc.reviewModal = true
        } else if (preCheckRes.status === 1) {
          // 有风险，进入确认页面
          this.drc.replicators.share = preCheckRes.workingReplicatorIps
          this.drc.replicators.conflictCurrent = preCheckRes.conflictMha === this.oldClusterName ? this.drc.replicators.old : this.drc.replicators.new
          this.drc.conflictMha = preCheckRes.conflictMha
          this.drc.warnModal = true
        } else {
          // 响应失败
          window.alert('config preCheck fail')
        }
      })
    },
    reviewConfigure () {
      this.drc.reviewModal = true
    },
    submitConfig () {
      const that = this
      console.log(this.drc.oldClusterName, this.drc.newClusterName)
      console.log(this.drc.replicators.old)
      console.log(this.drc.appliers.old)
      console.log(this.drc.oldNameFilter)
      console.log(this.drc.oldNameMapping)
      console.log(this.drc.oldApplyMode)
      console.log(this.drc.oldExecutedGtid)
      console.log(this.drc.replicators.new)
      console.log(this.drc.appliers.new)
      console.log(this.drc.newNameFilter)
      console.log(this.drc.newNameMapping)
      console.log(this.drc.newApplyMode)
      console.log(this.drc.newExecutedGtid)
      console.log(this.drc.oldTargetName)
      console.log(this.drc.newTargetName)
      this.axios.post('/api/drc/v1/meta/config', {
        srcMha: this.drc.oldClusterName,
        destMha: this.drc.newClusterName,
        srcReplicatorIps: this.drc.replicators.old,
        srcApplierIps: this.drc.appliers.old,
        srcApplierIncludedDbs: null,
        srcApplierNameFilter: this.drc.oldNameFilter,
        srcApplierNameMapping: this.drc.oldNameMapping,
        srcApplierApplyMode: this.drc.oldApplyMode,
        srcGtidExecuted: this.drc.oldExecutedGtid,
        srcClusterName: this.drc.oldTargetName,
        destReplicatorIps: this.drc.replicators.new,
        destApplierIps: this.drc.appliers.new,
        destApplierIncludedDbs: null,
        destApplierNameFilter: this.drc.newNameFilter,
        destApplierNameMapping: this.drc.newNameMapping,
        destApplierApplyMode: this.drc.newApplyMode,
        destGtidExecuted: this.drc.newExecutedGtid,
        destClusterName: this.drc.newTargetName
      }).then(response => {
        console.log(response.data)
        that.result = response.data.data
        that.drc.reviewModal = false
        that.drc.resultModal = true
      })
    },
    goToConfigRowsFiltersInSrcApplier () {
      console.log('go to change rowsFilter config for ' + this.newClusterName + '-> ' + this.oldClusterName)
      this.$router.push({ path: '/rowsFilterConfigs', query: { srcMha: this.newClusterName, destMha: this.oldClusterName } })
    },
    goToConfigRowsFiltersInDestApplier () {
      console.log('go to change rowsFilter config for ' + this.oldClusterName + '-> ' + this.newClusterName)
      this.$router.push({ path: '/rowsFilterConfigs', query: { srcMha: this.oldClusterName, destMha: this.newClusterName } })
    },
    goToConfigVosInSrcMha () {
      // this.$router.push({
      //   path: '/tables',
      //   query: {
      //     initInfo: {
      //       srcMha: 'srcMha',
      //       destMha: 'destMha',
      //       applierGroupId: 0,
      //       srcDc: 'srcDc',
      //       destDc: 'destDc',
      //       order: true
      //     }
      //   }
      // })
      this.axios.post('/api/drc/v1/build/simplexDrc?srcMha=' + this.oldClusterName + '&destMha=' + this.newClusterName)
        .then(response => {
          if (response.data.status === 1) {
            window.alert('获取或创建该方向同步失败!')
          } else {
            const vo = response.data.data
            console.log('go to change config for ' + this.oldClusterName + '-> ' + this.newClusterName)
            this.$router.push({
              path: '/tables',
              query: {
                srcMha: vo.srcMha,
                destMha: vo.destMha,
                srcMhaId: vo.srcMhaId,
                applierGroupId: vo.destApplierGroupId,
                srcDc: vo.srcDc,
                destDc: vo.destDc,
                order: true
              }
            })
          }
        })
    },
    goToConfigVosInDestMha () {
      // this.$router.push({
      //   path: '/tables',
      //   query: {
      //     initInfo: {
      //       srcMha: 'destMha',
      //       srcMhaId: 0,
      //       destMha: 'srcMha',
      //       applierGroupId: 0,
      //       srcDc: 'destDc',
      //       destDc: 'srcDc',
      //       order: false
      //     }
      //   }
      // })
      this.axios.post('/api/drc/v1/build/simplexDrc?srcMha=' + this.newClusterName + '&destMha=' + this.oldClusterName)
        .then(response => {
          if (response.data.status === 1) {
            window.alert('获取或创建该方向同步失败!')
          } else {
            const vo = response.data.data
            console.log('go to change config for ' + this.newClusterName + '-> ' + this.oldClusterName)
            this.$router.push({
              path: '/tables',
              query: {
                srcMha: vo.srcMha,
                destMha: vo.destMha,
                srcMhaId: vo.srcMhaId,
                applierGroupId: vo.destApplierGroupId,
                srcDc: vo.srcDc,
                destDc: vo.destDc,
                order: false
              }
            })
          }
        })
    },
    checkMysqlTablesInOldMha () {
      this.checkMySqlTables(this.drc.oldClusterName, this.drc.oldNameFilter)
    },
    checkMysqlTablesInNewMha () {
      this.checkMySqlTables(this.drc.newClusterName, this.drc.newNameFilter)
    },
    checkMySqlTables (mha, nameFilter) {
      console.log('nameFilter:' + nameFilter)
      if (nameFilter === undefined || nameFilter === null) {
        nameFilter = ''
      }
      this.$Spin.show({
        render: (h) => {
          return h('div', [
            h('Icon', {
              class: 'demo-spin-icon-load',
              props: {
                size: 18
              }
            }),
            h('div', '检测中，请稍等...')
          ])
        }
      })
      setTimeout(() => {
        this.$Spin.hide()
      }, 80000)
      this.axios.get('/api/drc/v1/build/preCheckMySqlTables?mha=' + mha +
        '&' + 'nameFilter=' + nameFilter)
        .then(response => {
          this.nameFilterCheck.tableData = response.data.data
          this.$Spin.hide()
          this.nameFilterCheck.modal = true
        })
    },
    handleChangeSize (val) {
      this.size = val
    }
  },
  created () {
    this.getResourcesInOld()
    this.getResourcesInUseInOld()
    this.getResourcesInNew()
    this.getResourcesInUseInNew()
  }
}
</script>
<style scoped>
.demo-split {
  height: 200px;
  border: 1px solid #dcdee2;
}

.demo-split-pane {
  padding: 10px;
}
</style>
