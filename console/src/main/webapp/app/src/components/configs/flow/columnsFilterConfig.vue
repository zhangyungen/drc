<template>
  <div>
    <Card dis-hover>
        <div slot="title">
          <span>字段过滤配置</span>
        </div>
        <Form ref="columnsFilterConfig" :model="columnsFilterConfig" :label-width="100"
              style="margin-top: 10px">
          <FormItem label="库名">
            <Input v-model="this.namespace" style="width:200px"  disabled/>
          </FormItem>
          <FormItem label="表名">
                <Input v-model="this.name" style="width:200px" disabled/>
          </FormItem>
          <FormItem label="模式">
            <Select v-model="columnsFilterConfig.mode" style="width: 200px" placeholder="选择行过滤模式">
              <Option v-for="item in modesForChose" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem label="字段">
            <Select  v-model="columnsFilterConfig.columns"  filterable allow-create
                     @on-create="handleCreateColumn" multiple style="width: 200px" placeholder="选择相关的字段">
              <Option v-for="item in columnsForChose" :value="item" :key="item">{{ item }}</Option>
            </Select>
          </FormItem>
          <FormItem>
            <Row>
              <Col span="10">
                <Button type="error" @click="deleteConfig" style="margin-left: 10px">删除</Button>
              </Col>
              <Col span="8">
                <Button type="primary" @click="handleSubmit('tableInfo')">保存</Button>
              </Col>
            </Row>
          </FormItem>
        </Form>
    </Card>
  </div>
</template>

<script>
export default {
  name: 'columnsFilterConfig',
  props: {
    srcMha: String,
    destMha: String,
    applierGroupId: Number,
    srcDc: String,
    destDc: String,
    dataMediaId: Number,
    namespace: String,
    name: String,
    tableData: Array
  },
  data () {
    return {
      columnsFilterConfig: {
        id: 0,
        mode: '',
        columns: []
      },
      modesForChose: [
        'exclude',
        'include',
        'regex'
      ],
      columnsForChose: []
    }
  },
  methods: {
    handleSubmit () {
      // todo
      this.submitConfig()
    },
    submitConfig () {
      this.axios.post('/api/drc/v1/dataMedia/columnsFilterConfig', {
        id: this.columnsFilterConfig.id,
        dataMediaId: this.dataMediaId,
        mode: this.columnsFilterConfig.mode,
        columns: this.columnsFilterConfig.columns
      }).then(response => {
        if (response.data.status === 1) {
          alert('提交失败!')
        } else {
          alert('提交成功！')
        }
      })
    },
    deleteConfig () {
      this.axios.delete('/api/drc/v1/dataMedia/columnsFilterConfig/' + this.columnsFilterConfig.id).then(response => {
        if (response.data.status === 1) {
          alert('删除失败!' + response.data.data)
        } else {
          this.getConfig()
          this.getCommonColumns()
          alert('删除成功！')
        }
      })
    },
    getConfig () {
      console.log(this.applierGroupId)
      this.axios.get('/api/drc/v1/dataMedia/columnsFilterConfig?dataMediaId=' + this.dataMediaId)
        .then(response => {
          if (response.data.status === 1) {
            alert('查询字段过滤配置失败!')
          } else {
            const res = response.data.data
            if (res == null) {
              // empty config
            } else {
              this.columnsFilterConfig.id = res.id
              this.columnsFilterConfig.mode = res.mode
              this.columnsForChose = res.columns
              this.columnsFilterConfig.columns = res.columns
            }
          }
        })
    },
    getCommonColumns () {
      console.log('/api/drc/v1/build/rowsFilter/commonColumns?' +
        '&mhaName=' + this.srcMha +
        '&namespace=' + this.namespace +
        '&name=' + this.name)
      this.axios.get('/api/drc/v1/build/rowsFilter/commonColumns?' +
        '&mhaName=' + this.srcMha +
        '&namespace=' + this.namespace +
        '&name=' + this.name)
        .then(response => {
          if (response.data.status === 1) {
            alert('查询公共列名失败，请手动添加！' + response.data.data)
          } else {
            console.log(response.data.data)
            this.columnsForChose = response.data.data
            if (this.columnsForChose.length === 0) {
              alert('查询无公共字段！')
            }
          }
        })
    },
    handleCreateColumn (val) {
      if (this.contains(this.columnsForChose, val)) {
        alert('已有项禁止创建')
        return
      }
      if (val === '' || val === undefined || val === null) {
        alert('字段不能为空')
        return
      }
      this.columnsForChose.push(val)
    },
    contains (a, obj) {
      var i = a.length
      while (i--) {
        if (a[i] === obj) {
          return true
        }
      }
      return false
    }
  },
  created () {
    this.getConfig()
    this.getCommonColumns()
  }
}
</script>

<style scoped>

</style>
